# Plan prezentacji: Index Lookup Optimization w MATCH

Wewnętrzny tech-talk, 35–40 min, audytorium zna SQL executor i MATCH.
Skrótem do całej linii pięciu PR-ów składających się na pre-filter:
#822 → #923 → #918 → #946 → #973.

## TL;DR jednym akapitem

MATCH ładował każdy vertex z dysku tylko po to, żeby go odrzucić filtrem.
Pięć PR-ów zamienia ten nested-loop na trzy mechanizmy działające **przed**
`loadEntity()`: filtr klasy po collection ID, intersekcję z RID-setem z indeksu,
intersekcję z reverse-adjacency listą. Następnie podbudowuje to o hash join dla
NOT/multi-branch/WHILE i back-reference, a na końcu reguluje cost-model tak, żeby
optymalizacja nie aktywowała się tam, gdzie szkodzi. Wynik IC5: 10–20× mniej
edge traversals, IC4/IC12/IC7 z O(upstream × subpattern) do O(build + probe).

## Czemu w ogóle taka prezentacja

Pre-filter to teraz centralny mechanizm w MatchEdgeTraverser i ExpandStep.
Każdy, kto będzie czytał plan EXPLAIN dla MATCH-a albo debugował regresję
selektywności, musi rozumieć ten stack. Audytorium dostaje:

- Wspólny model mentalny: cztery typy descryptorów, jeden punkt decyzji
  (`MatchExecutionPlanner.optimizeScheduleWithIntersections`).
- Skąd biorą się liczby w PROFILE (`applied=`, `skipped=`, `filterRate=`).
- Co właściwie znaczy `BUILD_EAGER` vs `DEFERRED_WITH_NET` i kiedy plan-cache
  każdego z trybów ratuje albo zabija query (#973).
- Jak rozszerzyć system o nowy typ descryptora bez wybijania
  istniejących gwarancji selektywności.

## Mapa pięciu PR-ów

| # | YTDB | Tytuł | Stan | Rola |
|---|---|---|---|---|
| 822 | 603+587+591 | Predicate push-down + adjacency intersection | merged 03-25 | foundation: 3 typy descryptorów, helper, planner pass |
| 923 | 645 | Comprehensive MATCH pre-filter test suite | merged 04-08 | 90 testów + DirectRid singleton + BETWEEN-jako-range |
| 918 | 592 | Hash join dla NOT i multi-branch MATCH | merged 04-15 | IC4 anti-join, IC12 inverted WHILE, IC7 correlated optional |
| 946 | 650 | Back-reference hash join | merged 04-22 | IC5/IC10 patterns A/B/D, per-binding LRU cache |
| 973 | 651 | Prefilter selectivity fix | **open** | per-descriptor selectivity, CLT gate, amortyzacja, observability |

Linia czasu pokazuje narrację: budowa → walidacja → rozszerzenie zakresu
(hash join) → tuning kosztu. Każdy następny PR opiera się na wynikach
poprzedniego.

## Struktura prezentacji (40 min)

### Część 0 — Setup (3 min, 2 slajdy)

1. **Title + TL;DR jednym zdaniem.** „Pięć PR-ów zamienia per-row scan na
   pre-filtrowanie przed loadEntity."
2. **Pre-flight contekst.** Czym jest MATCH, czym jest LinkBag,
   gdzie żyje VertexFromLinkBagIterator, czemu IC5 jest naszą wartością
   referencyjną. Jeden diagram ścieżki LinkBag → loadEntity → filter.

### Część 1 — Problem (5 min, 3 slajdy)

3. **IC5 jako anchor case.** Pokazać query (forum → posts → creator =
   $matched.person). Liczby: ~700K LinkBag entry scans, 96% odrzuconych przez
   filter WHERE.
4. **Trzy klasy bottleneck'ów wyciągnięte z LDBC.** IC5/IC10 back-reference,
   IC3/IC5 `WHERE @class = 'Post'` po expand(), IC3/IC5 index-backed date
   range. Każdy z innym kosztem (vertex load vs edge scan vs index miss).
5. **Czemu nested-loop, czemu nikt tego nie naprawił wcześniej.**
   Mechanizm scheduling MATCH dostarcza edges w kolejności kosztowej, ale po
   wybraniu edge'a traversal nie patrzy już na klasę targetu ani na to, że
   jakaś alias dalej w pattern wymusza `@rid = $matched.X.@rid`.

Sekcja zamyka się hipotezą: jeśli przed `loadEntity()` damy filtr, który da się
oszacować jako selektywny, IC5 powinna spaść 10×.

### Część 2 — PR #822: foundation pre-filtra (8 min, 6 slajdów)

6. **Trzy typy descryptorów — pierwszy slajd architektury.**

   - Case A: class filter, sprawdzany w iteratorze LinkBag przez collection ID
     (zero I/O).
   - Case B: index RID set, dowolne indexed property condition
     (equality/range/IN).
   - Case C: back-reference, czyli `out('E').@rid = $matched.X.@rid`
     rozwiązane jako reverse LinkBag → RID set.

   Wszystko trafia jako pole `acceptedCollectionIds` / `acceptedRids`
   na `VertexFromLinkBagIterator`. Test jest w iteratorze, nie w filtrze
   step-a.

7. **Wspólny helper.** `TraversalPreFilterHelper` jako fabryka descryptorów.
   Używany przez `ExpandStep` (dla `SELECT expand(out('E'))`) i przez
   `MatchEdgeTraverser`. Pokazać dwa call-sites — symetria nie jest
   przypadkowa, oszczędza nam podwójnego utrzymania.

8. **Planner pass: `optimizeScheduleWithIntersections`.**
   Działa po cost-based edge reorderingu. Każda zaplanowana krawędź dostaje
   pytanie: czy target ma WHERE, którego selectivity estimate jest poniżej
   progu? Jeśli tak, doczepia descryptor.

9. **Adjacency intersection w praktyce.** Slide z dwoma LinkBagami: forward
   (forum → posts) i reverse (person → posts). Intersekcja daje O(posts_by_person)
   zamiast O(all_posts_in_forum). `RidSet` (#781) jest tu kluczowy — IntMap +
   Roaring64Bitmap płaski przebieg.

10. **Slide z liczbami.** IC5: 700K → ~40K traversals. IC3 z `@class = 'Post'`:
    9997 nieodczytanych dokumentów na zapytanie. IC5 z date range: index zwraca
    ~3% targetów.

11. **Co PR #822 zostawił na talerzu.** Selectivity check jednym wzorem
    `ridSetSize / linkBagSize` dla wszystkich typów. RID literal i parameter
    nieobsłużone (`@rid = #12:0` przechodził obok). BETWEEN nie był rozpoznawany
    jako range. To zostanie ustawione w #923 i #973.

### Część 3 — PR #923: validation, czyli „a co jak ta optymalizacja nie wstaje" (4 min, 3 slajdy)

12. **Po co 90 testów.** Pre-filter to feature, którego nie widać w wyniku —
    widać go tylko w EXPLAIN. Bez systematycznej walidacji następna optymalizacja
    może go cicho wyłączyć w 30% przypadków i nikt się nie dowie.

13. **Co testy pokrywają.** Cztery typy descryptorów × 11 topologii grafu ×
    6 metod krawędziowych × 20 negative testów (gdzie optymalizacja **nie** ma
    triggerować — both(), WHILE, brak indeksu, $currentMatch, OR w WHERE,
    untyped edge). Każdy negative test ma komentarz „dlaczego nie".

14. **Dwa małe fixy odkryte przy pisaniu testów.**

    - **DirectRid singleton intersection**: `WHERE @rid = #12:0` lub `@rid = :param`
      dotąd były ignorowane. Planner robi teraz singleton descryptor.
    - **BETWEEN jako range**: `SQLBetweenCondition.flatten()` rozpisuje
      `val BETWEEN low AND high` na `val >= low AND val <= high`, dzięki czemu
      istniejąca infra indeksowa łapie BETWEEN bez nowego kodu.

    Lekcja: pisanie testów end-to-end na EXPLAIN wykrywa dziury, których
    review nie złapie.

### Część 4 — PR #918: hash join dla wzorców niezależnych od $matched (6 min, 5 slajdów)

15. **Drugie ograniczenie: NOT, multi-branch, WHILE, correlated optional.**
    PR #822 pomógł tam, gdzie target ma WHERE rozpoznawalne jako filtr.
    Nie pomógł tam, gdzie cały sub-pattern się re-egzekwuje dla każdego upstream
    row. IC4 NOT pattern. IC12 WHILE recursion. IC7 optional z $matched.@rid.

16. **Pięć patternów, trzy step-y.**

    - `HashJoinMatchStep` w trybach ANTI_JOIN (IC4), SEMI_JOIN (multi-branch),
      INNER_JOIN (multi-branch z RETURN).
    - `InvertedWhileHashJoinStep` (IC12): odwraca kierunek WHILE z DFS-per-row
      na BFS-once + hash filter.
    - `CorrelatedOptionalHashJoinStep` (IC7): leniwy build z pierwszego
      upstream row, LEFT semantics.

17. **JoinKey.** Kind-tagged dispatch z precomputed hash i zero-allocation fast
    path dla single-RID. Hot loop probe-side bez alokacji.

18. **Build-side context isolation.** Plan dla branch buduje się z
    `BasicCommandContext.setParentWithoutOverridingChild(ctx)`, żeby
    `:startDate` / `:endDate` z parametów się przeniosły, ale `$matched` nie
    pollutowało. Trzy alignment points wymagane do działania reverse-edge:
    `leftClass`/`leftFilter`/`leftRid`.

19. **Eligibility check + runtime safety.** Planner wybiera hash join tylko gdy
    brak zależności od `$matched` lub zależność jest na already-bound
    single-valued alias **oraz** estimated build cardinality < 10K. Estymator
    fan-out z `EdgeFanOutEstimator`, selectivity z `EquiDepthHistogram` (#772)
    albo `defaultSelectivity`. Jeśli runtime przekroczy próg — automatic
    fallback do nested-loop, korektność gwarantowana niezależnie od estymaty.

### Część 5 — PR #946: back-reference hash join (5 min, 4 slajdy)

20. **Trzecie ograniczenie: back-ref edges.** IC5 końcowa krawędź
    `.out('HAS_CREATOR'){where: @rid = $matched.person.@rid}`. PR #918 nie obejmował
    tego, bo build-side zależy od `$matched.person` — innej osoby per upstream
    row. Per-row scan reverse LinkBag → 96% rejection.

21. **Trzy patterny A/B/D.**

    - Pattern A — pojedyncza krawędź back-ref: build to reverse LinkBag jako
      `Set<RID>`, probe `source ∈ set`.
    - Pattern B — outE+inV chain z edge properties: build `Map<RID, List<Edge>>`
      przez reverse LinkBag + opcjonalny indeks. Edge collapsing — krawędź
      `.outE()` markowana `consumed`, jeden step pokrywa obie krawędzie.
    - Pattern D — NOT IN: `where: ($currentMatch NOT IN $matched.X.out('E'))`
      jako anti-join. NOT IN stripping z WHERE w MatchStep, żeby uniknąć
      O(degree) per row evaluation.

22. **Per-binding LRU cache (capacity 256).** IC5: ~58 distinct persons,
    każda generuje ~5K triples. Cache po `bindingRid` zwraca raz zbudowany
    hash table — bez tego re-build dla każdej z 5K triple'i.

23. **`SemiJoinDescriptor` sealed interface.** Records: `SingleEdgeSemiJoin`,
    `ChainSemiJoin`, `AntiSemiJoin`. Carry planner metadata przez `EdgeTraversal`
    do runtime. Exhaustive `switch` dispatch w `BackRefHashJoinStep` — kompilator
    pilnuje pełnego pokrycia.

### Część 6 — PR #973: cost-model tuning (8 min, 7 slajdów) — najświeższe

24. **Problem, który ujawniło #946.** Wszystkie typy descryptorów dzieliły
    jedną formułę selectivity `ridSetSize / linkBagSize`. To nie jest błędne
    dla `EdgeRidLookup` (per-vertex overlap ma sens), ale **całkowicie zła
    semantyka dla `IndexLookup`**: index-based filter ma class-level
    selectivity (stała w query), nie per-vertex overlap.

25. **Per-descriptor selectivity.** `RidFilterDescriptor.passesSelectivityCheck`
    dispatch po typie. `DirectRid` zawsze passes. `EdgeRidLookup` używa
    `edgeLookupMaxRatio` (0.8). `IndexLookup` używa `indexLookupMaxSelectivity`
    (0.95). `Composite` passes jeśli którekolwiek dziecko passes.

26. **Drugi problem: koszt budowy RidSet-a.** IndexLookup materializuje cały
    RidSet eagerly przy pierwszym kwalifikującym się vertexie. Jeśli traversal
    obejmie tylko kilka vertexów, jednorazowy koszt buildu nigdy się nie zwróci.
    Wzór break-even: `m = estimatedSize / (loadToScanRatio × (1 - selectivity))`.

27. **Dwa tryby amortyzacji.** Memoized per edge na pierwszym wywołaniu:

    - `BUILD_EAGER` gdy `forecastN > ceil(m)` **i** `rootSourceRows >= MIN_FOR_CLT`
      (= 30): materialize na pierwszym kwalifikującym vertex.
    - `DEFERRED_WITH_NET` w przeciwnym wypadku: akumuluj `linkBagSize` per
      vertex, materialize gdy total osiągnie `T = max(2·forecastN, m)`.

28. **CLT confidence gate.** `forecastN = sourceRows × fanOut_mean`. Względny
    błąd skaluje się jako `CV_fanOut / sqrt(N)`. LDBC SNB ma heavy-tail
    (messages-per-person, power-law), więc małe `N` daje praktycznie losowy
    forecast. Próg 30 jest klasyczny dla CLT — poniżej decyzja zjeżdża do
    DEFERRED_WITH_NET, gdzie runtime accumulator i safety net (2·forecastN)
    łapią rzeczywistość.

29. **Unknown selectivity → REJECT.** Gdy `estimateSelectivity` zwraca `-1`
    (brak histogramu, never-loaded index), wcześniej był optimistic PROCEED,
    bo `maxRidSetSize` był hardcoded 100K cap. Po auto-scaling capa (do 10M)
    bezpiecznik zniknął. Teraz REJECT z `STATS_UNAVAILABLE`, cached na klasę.

30. **Observability w PROFILE.** Per-edge counters:
    `preFilterAppliedCount`, `preFilterSkippedCount`, `preFilterTotalProbed`,
    `preFilterTotalFiltered`, `preFilterBuildTimeNanos`, `preFilterRidSetSize`.
    `PreFilterSkipReason` enum (8 wartości). Global `PREFILTER_EFFECTIVENESS`
    metric. PROFILE output: `applied=`, `skipped=`, `ridSetSize=`, `filterRate=`,
    plus diagnostic `NEVER APPLIED (reason: X, threshold=Y)`.

### Część 7 — Closing (3 min, 3 slajdy)

31. **Zsumowane liczby.** IC5: 10–20×. IC4/IC12/IC7: O(upstream × subpattern)
    → O(build + probe). IC3/IC10: 9997 nie-loadów. PR #973 nie dodaje nowych
    wygranych, ale eliminuje regresje na queries z brakującymi statystykami.

32. **Co dalej.**

    - Live cost measurement z runtime metrics (zaplanowane, nie w #973).
    - Histogram coverage dla edge properties (`EdgeFanOutEstimator` ciągle
      class-level).
    - Cache reuse między query (dziś per-binding LRU jest per-execution).

33. **Reading order do walidacji.** Sześć plików w kolejności:

    - `TraversalPreFilterHelper.java`
    - `MatchExecutionPlanner.optimizeScheduleWithIntersections`
    - `EdgeTraversal.resolveWithCache`
    - `RidFilterDescriptor.passesSelectivityCheck`
    - `BackRefHashJoinStep` + `HashJoinMatchStep` + `InvertedWhileHashJoinStep`
      + `CorrelatedOptionalHashJoinStep`
    - `MatchStep.appendPreFilterStats` (= co widać w PROFILE)

## Materiały, z których ciągniemy slajdy

- PR #822 body — diagram trzech case'ów + lista key new components.
- PR #923 body — tabela 4 typy × 11 topologii × 6 metod + lista 20 negative
  patternów.
- PR #918 body — tabela pięciu patternów + step → IC mapping.
- PR #946 body — tabela patternów A/B/D + per-binding cache rationale.
- PR #973 body — wzór `m`, CLT rationale, lista skip reasons.
- `docs/adr/prefilter-ic2-analysis.md` (lokalna, niezacommitowana) — case
  study IC2 jako kontrolny przykład gdzie pre-filter się **nie** włącza.
- `docs/adr/prefilter-load-cost-measurement.md` (lokalna) — pomiary
  load-to-scan ratio, źródło defaultu 100.0.
- `docs/adr/index-assisted-traversal/design-final.md` — durable ADR po
  YTDB-603.

## Diagramy do narysowania

1. **LinkBag → loadEntity → filter (slajd 2).** Strzałka z napisem
   „90% kosztu" na `loadEntity`.
2. **Trzy case'y descryptorów (slajd 6).** Trzy kolumny: Class / Index /
   BackRef. Każda z miejscem testu (collection ID / `acceptedRids` / reverse
   LinkBag intersection).
3. **Forward × reverse LinkBag intersection (slajd 9).** Dwa LinkBagi, część
   wspólna jako kropki w środku.
4. **Pięć patternów hash join (slajd 16).** Tabela z kolumnami: pattern, IC,
   build-side, probe-side, step class.
5. **Patterny A/B/D back-ref (slajd 21).** Trzy kolumny — pattern, AST shape,
   build → probe.
6. **BUILD_EAGER vs DEFERRED_WITH_NET decision tree (slajd 27).** Trzy
   warunki: `forecastN > ceil(m)`, `rootSourceRows >= 30`, `selectivity != -1`.
7. **CLT distribution gate (slajd 28).** Histogram fan-out z LDBC + zaznaczona
   sample size 30.

## Backup slajdy (na pytania)

- `EdgeTraversal.cache` jako per-edge LRU dla descryptorów (cap 64).
- `RidSet` budowa: IntMap + Roaring64Bitmap, czemu nie HashSet (#781).
- `IndexSearchDescriptor.cacheFingerprint` w #973 — dlaczego sam `index.getName()`
  to za mało (latent collision).
- `cachedSkipReasons` map — czemu cached-null musi pamiętać powód
  (interleaved skip mask issue).
- `stampEdgeForecasts` short-circuit — czemu `Long.MAX_VALUE` strip jest
  istotny dla planów z `inferredWhileExprAliases`.

## Pre-flight checklist

- [ ] Załatać PROFILE output z prawdziwego LDBC IC5 — slajd 4 i slajd 30
      muszą mieć rzeczywiste liczby, nie estymaty z PR body.
- [ ] Wyrenderować jeden EXPLAIN dla IC5 przed i po #822 — slajd 11.
- [ ] Wyrenderować jeden EXPLAIN dla IC4 przed i po #918 — slajd 18.
- [ ] Sprawdzić, czy LDBC benchmark wyniki z #946 (Hetzner) są nadal w
      komentarzach PR-a — slajd 23.
- [ ] PR #973 nie jest jeszcze zmergowany; w prezentacji jasno zaznaczyć
      to jako „pending merge". Liczby observability z PROFILE są realne (są
      w testach), ale narrację dopinamy po merge.

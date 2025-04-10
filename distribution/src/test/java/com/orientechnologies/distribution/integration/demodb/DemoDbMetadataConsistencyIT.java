package com.orientechnologies.distribution.integration.demodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaInternal;
import com.orientechnologies.distribution.integration.IntegrationTestTemplate;
import org.junit.Test;

/**
 *
 */
public class DemoDbMetadataConsistencyIT extends IntegrationTestTemplate {

  private int vCount = 7275;
  private int locationsCount = 3541;
  private int attractionsCount = 436;
  private int archSitesCount = 55;
  private int castlesCount = 127;
  private int monumentsCount = 137;
  private int theatresCount = 117;
  private int ServicesCount = 3105;
  private int hotelsCount = 1154;
  private int restaurantsCount = 1951;
  private int profilesCount = 1000;
  private int customersCount = 400;
  private int countriesCount = 249;
  private int ordersCount = 812;
  private int reviewsCount = 1273;

  private int eCount = 14872;
  private int hasCustomerCount = 812;
  private int hasEatenCount = 2479;
  private int hasFriendCount = 1617;
  private int hasProfileCount = 400;
  private int hasReviewCount = 1273;
  private int hasStayedCount = 1645;
  private int hasUsedServiceCount = 4124;
  private int hasVisitedCount = 4973;
  private int isFromCountryCount = 400;
  private int madeReviewCount = 1273;

  @Test
  public void testMetadata() throws Exception {

    SchemaInternal schema = session.getMetadata().getSchema();

    // todo: properties & indices

    // vertices

    final var v = ((SchemaClassInternal) schema.getClass("V"));
    assertThat(v).isNotNull();
    assertThat(v.getSubclasses()).hasSize(14);
    assertThat(v.count(session)).isEqualTo(vCount);

    final var locations = ((SchemaClassInternal) schema.getClass("Locations"));
    assertThat(locations).isNotNull();
    assertEquals("V", locations.getSuperClassesNames().get(0));
    assertThat(locations.getSubclasses()).hasSize(2);
    assertThat(locations.count(session)).isEqualTo(locationsCount);

    final var attractions = (SchemaClassInternal) schema.getClass("Attractions");
    assertThat(attractions).isNotNull();
    assertEquals("V", attractions.getSuperClassesNames().get(0));
    assertEquals("Locations", attractions.getSuperClassesNames().get(1));
    assertThat(attractions.getSubclasses()).hasSize(4);
    assertThat(attractions.count(session)).isEqualTo(attractionsCount);

    final var archaeologicalSites = (SchemaClassInternal) schema.getClass("ArchaeologicalSites");
    assertThat(archaeologicalSites).isNotNull();
    assertEquals("V", archaeologicalSites.getSuperClassesNames().get(0));
    assertEquals(
        "Attractions", archaeologicalSites.getSuperClassesNames().get(1));
    assertThat(archaeologicalSites.count(session)).isEqualTo(archSitesCount);

    final var castles = (SchemaClassInternal) schema.getClass("Castles");
    assertThat(castles).isNotNull();
    assertEquals("V", castles.getSuperClassesNames().get(0));
    assertEquals("Attractions", castles.getSuperClassesNames().get(1));
    assertThat(castles.count(session)).isEqualTo(castlesCount);

    final var monuments = (SchemaClassInternal) schema.getClass("Monuments");
    assertThat(monuments).isNotNull();
    assertEquals("V", monuments.getSuperClassesNames().get(0));
    assertEquals("Attractions", monuments.getSuperClassesNames().get(1));
    assertThat(monuments.count(session)).isEqualTo(monumentsCount);

    final var theatres = (SchemaClassInternal) schema.getClass("Theatres");
    assertThat(theatres).isNotNull();
    assertEquals("V", theatres.getSuperClassesNames().get(0));
    assertEquals("Attractions", theatres.getSuperClassesNames().get(1));
    assertThat(theatres.count(session)).isEqualTo(theatresCount);

    final var services = (SchemaClassInternal) schema.getClass("Services");
    assertThat(services).isNotNull();
    assertEquals("V", services.getSuperClassesNames().get(0));
    assertEquals("Locations", services.getSuperClassesNames().get(1));
    assertThat(services.getSubclasses()).hasSize(2);
    assertThat(services.count(session)).isEqualTo(ServicesCount);

    final var hotels = (SchemaClassInternal) schema.getClass("Hotels");
    assertThat(hotels).isNotNull();
    assertEquals("V", hotels.getSuperClassesNames().get(0));
    assertThat(hotels.count(session)).isEqualTo(hotelsCount);

    final var restaurants = (SchemaClassInternal) schema.getClass("Restaurants");
    assertThat(restaurants).isNotNull();
    assertEquals("V", restaurants.getSuperClassesNames().get(0));
    assertThat(restaurants.count(session)).isEqualTo(restaurantsCount);

    final var profiles = (SchemaClassInternal) schema.getClass("Profiles");
    assertThat(profiles).isNotNull();
    assertEquals("V", profiles.getSuperClassesNames().get(0));
    assertThat(profiles.count(session)).isEqualTo(profilesCount);

    final var customers = (SchemaClassInternal) schema.getClass("Customers");
    assertThat(customers).isNotNull();
    assertEquals("V", customers.getSuperClassesNames().get(0));
    assertThat(customers.count(session)).isEqualTo(customersCount);

    final var countries = (SchemaClassInternal) schema.getClass("Countries");
    assertThat(countries).isNotNull();
    assertEquals("V", countries.getSuperClassesNames().get(0));
    assertThat(countries.count(session)).isEqualTo(countriesCount);

    final var orders = (SchemaClassInternal) schema.getClass("Orders");
    assertThat(orders).isNotNull();
    assertEquals("V", orders.getSuperClassesNames().get(0));
    assertThat(orders.count(session)).isEqualTo(ordersCount);

    final var reviews = (SchemaClassInternal) schema.getClass("Reviews");
    assertThat(reviews).isNotNull();
    assertEquals("V", reviews.getSuperClassesNames().get(0));
    assertThat(reviews.count(session)).isEqualTo(reviewsCount);
    //

    // edges
    final var e = ((SchemaClassInternal) schema.getClass("E"));
    assertThat(e).isNotNull();
    assertThat(e.getSubclasses()).hasSize(10);
    assertThat(e.count(session)).isEqualTo(eCount);

    final var hasCustomer = (SchemaClassInternal) schema.getClass("HasCustomer");
    assertThat(hasCustomer).isNotNull();
    assertEquals("E", hasCustomer.getSuperClassesNames().get(0));
    assertThat(hasCustomer.count(session)).isEqualTo(hasCustomerCount);

    final var hasEaten = (SchemaClassInternal) schema.getClass("HasEaten");
    assertThat(hasEaten).isNotNull();
    assertEquals("E", hasEaten.getSuperClassesNames().get(0));
    assertEquals("HasUsedService", hasEaten.getSuperClassesNames().get(1));
    assertThat(hasEaten.count(session)).isEqualTo(hasEatenCount);

    final var hasFriend = (SchemaClassInternal) schema.getClass("HasFriend");
    assertThat(hasFriend).isNotNull();
    assertEquals("E", hasFriend.getSuperClassesNames().get(0));
    assertThat(hasFriend.count(session)).isEqualTo(hasFriendCount);

    final var hasProfile = (SchemaClassInternal) schema.getClass("HasProfile");
    assertThat(hasProfile).isNotNull();
    assertEquals("E", hasProfile.getSuperClassesNames().get(0));
    assertThat(hasProfile.count(session)).isEqualTo(hasProfileCount);

    final var hasReview = (SchemaClassInternal) schema.getClass("HasReview");
    assertThat(hasReview).isNotNull();
    assertEquals("E", hasReview.getSuperClassesNames().get(0));
    assertThat(hasReview.count(session)).isEqualTo(hasReviewCount);

    final var hasStayed = (SchemaClassInternal) schema.getClass("HasStayed");
    assertThat(hasStayed).isNotNull();
    assertEquals("E", hasStayed.getSuperClassesNames().get(0));
    assertEquals("HasUsedService", hasStayed.getSuperClassesNames().get(1));
    assertThat(hasStayed.count(session)).isEqualTo(hasStayedCount);

    final var hasUsedService = (SchemaClassInternal) schema.getClass("HasUsedService");
    assertThat(hasUsedService).isNotNull();
    assertEquals("E", hasUsedService.getSuperClassesNames().get(0));
    assertThat(hasUsedService.getSubclasses()).hasSize(2);
    assertThat(hasUsedService.count(session)).isEqualTo(hasUsedServiceCount);

    session.executeInTx(tx -> {
      // other way to check inheritance
      final var results =
          session
              .query(
                  "SELECT DISTINCT(@class) AS className from `HasUsedService` ORDER BY className ASC")
              .toList();
      assertEquals(2, results.size());
      assertEquals("HasEaten", results.get(0).getProperty("className"));
      assertEquals("HasStayed", results.get(1).getProperty("className"));
    });

    final var hasVisited = (SchemaClassInternal) schema.getClass("HasVisited");
    assertThat(hasVisited).isNotNull();
    assertEquals("E", hasVisited.getSuperClassesNames().get(0));
    assertThat(hasVisited.count(session)).isEqualTo(hasVisitedCount);

    final var isFromCountry = (SchemaClassInternal) schema.getClass("IsFromCountry");
    assertThat(isFromCountry).isNotNull();
    assertEquals("E", isFromCountry.getSuperClassesNames().get(0));
    assertThat(isFromCountry.count(session)).isEqualTo(isFromCountryCount);

    final var madeReview = (SchemaClassInternal) schema.getClass("MadeReview");
    assertThat(madeReview).isNotNull();
    assertEquals("E", madeReview.getSuperClassesNames().get(0));
    assertThat(madeReview.count(session)).isEqualTo(madeReviewCount);

  }

  @Test
  public void testDataModel() throws Exception {

    session.executeInTx(tx -> {
      // all customers have a country
      final var resultSet =
          session.query(
              "MATCH {class: Customers, as: customer}-IsFromCountry->{class: Countries, as: country}"
                  + " RETURN  customer");
      assertThat(resultSet).hasSize(customersCount);
    });

    session.executeInTx(tx -> {
      // all customers have a profile
      final var resultSet =
          session.query(
              "MATCH {class: Customers, as: customer}-HasProfile->{class: Profiles, as: profile}"
                  + " RETURN customer");
      assertThat(resultSet).hasSize(customersCount);
    });

    session.executeInTx(tx -> {
      // all customers have at least 1 order
      final var resultSet =
          session.query(
              "MATCH {class: Orders, as: order}-HasCustomer->{class: Customers, as:customer} RETURN"
                  + " order");
      assertThat(resultSet.stream().count()).isGreaterThan(customersCount);
    });
  }

  @Test
  public void testMatchWithConditionInBackTraversal() throws Exception {
    session.executeInTx(tx -> {
      final var resultSet =
          session.query(
              "MATCH \n"
                  + "{class:Profiles, as:profileA} <-HasProfile- {as:customerA} -MadeReview->"
                  + " {as:reviewA} <-HasReview- {as:restaurant},\n"
                  + "{as:profileB, where:($matched.profileA != $currentMatch)} <-HasProfile-"
                  + " {as:customerB} -MadeReview-> {as:reviewB} <-HasReview- {as:restaurant}\n"
                  + "return  profileA.Id as idA, profileA.Name, profileA.Surname, profileB.Id as idA,"
                  + " profileB.Name, profileB.Surname\n"
                  + "limit 10\n");

      int size = 0;
      while (resultSet.hasNext()) {
        final var item = resultSet.next();
        assertThat((Object) item.getProperty("idA")).isNotEqualTo(item.getProperty("idB"));
        size++;
      }
      assertThat(size).isEqualTo(10);
    });
  }
}

package com.orientechnologies.distribution.integration.demodb;

import com.orientechnologies.distribution.integration.IntegrationTestTemplate;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DemoDbFromDocumentationCustomersIT extends IntegrationTestTemplate {

  @Test
  public void test_Customers_Example_1() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {class: Customers, as: c, where: (OrderedId=1)}--{as: n} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 22);
    });
  }

  @Test
  public void test_Customers_Example_2() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {class: Customers, as: customer, where: (OrderedId=1)}--{Class: Locations} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 16);

    });
  }

  @Test
  public void test_Customers_Example_3() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {class: Customers, as: c, where: (OrderedId=1)}--{class: Locations, as:"
                  + " loc}-HasReview->{class: Reviews, as: r, optional: true} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 21);

    });
  }

  @Test
  public void test_Customers_Example_4() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {class: Customers, as: customer, where: (OrderedId=1)}--{class: Locations, as:"
                  + " loc}--{class: Customers, as: otherCustomers, where: (OrderedId<>1)} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 150);

    });
  }

  @Test
  public void test_Customers_Example_5() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {as: n}<-HasStayed-{class: Customers, as: c, where: (OrderedId=2)} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 12);

    });
  }

  @Test
  public void test_Customers_Example_6() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {as: n}<-HasEaten-{class: Customers, as: c, where: (OrderedId=1)} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 6);

    });
  }

  @Test
  public void test_Customers_Example_7() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  OrderedId as CustomerId,\n"
                  + "  out(\"MadeReview\").size() AS ReviewNumbers \n"
                  + "FROM `Customers` \n"
                  + "ORDER BY ReviewNumbers DESC \n"
                  + "LIMIT 3");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 3);

      final var result = results.iterator().next();

      Assert.assertEquals(result.<Long>getProperty("CustomerId"), Long.valueOf(384));
      Assert.assertEquals(result.<Integer>getProperty("ReviewNumbers"), Integer.valueOf(29));

    });
  }

  @Test
  public void test_Customers_Example_8() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {class: Customers, as: c, where: (OrderedId=1)}<-HasCustomer-{class: Orders, as:"
                  + " o} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 2);

    });
  }

  @Test
  public void test_Customers_Example_9() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT sum(Amount) as TotalAmount\n"
                  + "FROM (\n"
                  + "  SELECT expand(in('HasCustomer'))\n"
                  + "  FROM Customers\n"
                  + "  WHERE OrderedId=2\n"
                  + ")");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 1);

      final var result = results.iterator().next();

      Assert.assertEquals(result.<Long>getProperty("TotalAmount"), Long.valueOf(1750));

    });
  }

  @Test
  @Ignore
  public void test_Customers_Example_10() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  OrderedId as CustomerId,\n"
                  + "  in(\"HasCustomer\").size() AS NumberOfOrders \n"
                  + "FROM Customers \n"
                  + "ORDER BY NumberOfOrders \n"
                  + "DESC LIMIT 3");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 3);

      final var result = results.iterator().next();

      Assert.assertEquals(result.<Long>getProperty("CustomerId"), Long.valueOf(113));
      Assert.assertEquals(result.<Integer>getProperty("NumberOfOrders"), Integer.valueOf(6));

    });
  }

  @Test
  public void test_Customers_Example_11() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  Name as CountryName,\n"
                  + "  in('IsFromCountry').size() as NumberOfCustomers \n"
                  + "FROM Countries\n"
                  + "ORDER BY NumberOfCustomers DESC \n"
                  + "LIMIT 3");

      final var results = resultSet.stream().collect(Collectors.toList());
      Assert.assertEquals(results.size(), 3);

      final var result = results.iterator().next();

      Assert.assertEquals(result.getProperty("CountryName"), "Kyrgyzstan");
      Assert.assertEquals(result.<Integer>getProperty("NumberOfCustomers"), Integer.valueOf(7));

    });
  }
}

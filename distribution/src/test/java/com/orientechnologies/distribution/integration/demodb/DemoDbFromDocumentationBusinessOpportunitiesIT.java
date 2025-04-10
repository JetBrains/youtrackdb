package com.orientechnologies.distribution.integration.demodb;

import com.orientechnologies.distribution.integration.IntegrationTestTemplate;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DemoDbFromDocumentationBusinessOpportunitiesIT extends IntegrationTestTemplate {

  @Test
  public void test_BusinessOpportunities_Example_1() throws Exception {

    session.executeInTx(tx -> {
      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  @Rid as Friend_RID, \n"
                  + "  Name as Friend_Name, \n"
                  + "  Surname as Friend_Surname \n"
                  + "FROM (\n"
                  + "  SELECT expand(customerFriend) \n"
                  + "  FROM (\n"
                  + "    MATCH {Class:Customers, as: customer,"
                  + " where:(OrderedId=1)}-HasProfile-{Class:Profiles, as:"
                  + " profile}-HasFriend-{Class:Profiles, as: customerFriend} RETURN customerFriend\n"
                  + "  )\n"
                  + ") \n"
                  + "WHERE in('HasProfile').size()=0\n"
                  + "ORDER BY Friend_Name ASC");

      final var results = resultSet.toList();
      Assert.assertEquals(5, results.size());

      final var result = results.getFirst();

      Assert.assertEquals("Emanuele", result.getProperty("Friend_Name"));
      Assert.assertEquals("YouTrackDB", result.getProperty("Friend_Surname"));

    });
  }

  @Test
  public void test_BusinessOpportunities_Example_2() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT DISTINCT * FROM (\n"
                  + "  SELECT expand(customerFriend) \n"
                  + "  FROM ( \n"
                  + "    MATCH \n"
                  + "      {Class:Customers, as: customer}-HasProfile-{Class:Profiles, as:"
                  + " profile}-HasFriend-{Class:Profiles, as: customerFriend} \n"
                  + "    RETURN customerFriend\n"
                  + "  )\n"
                  + ") \n"
                  + "WHERE in('HasProfile').size()=0");

      final var results = resultSet.toList();
      Assert.assertEquals(376, results.size());
    });
  }
}

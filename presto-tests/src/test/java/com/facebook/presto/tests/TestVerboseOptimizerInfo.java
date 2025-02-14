/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.presto.tests;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.SessionPropertyManager;
import com.facebook.presto.spi.ConnectorId;
import com.facebook.presto.testing.LocalQueryRunner;
import com.facebook.presto.testing.MaterializedResult;
import com.facebook.presto.testing.QueryRunner;
import com.facebook.presto.tpch.TpchConnectorFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.facebook.presto.SystemSessionProperties.OPTIMIZE_PAYLOAD_JOINS;
import static com.facebook.presto.testing.TestingSession.TESTING_CATALOG;
import static com.facebook.presto.testing.TestingSession.testSessionBuilder;
import static com.facebook.presto.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static com.google.common.collect.Iterables.getOnlyElement;
import static org.testng.Assert.assertTrue;

public class TestVerboseOptimizerInfo
        extends AbstractTestQueries
{
    @Override
    protected QueryRunner createQueryRunner()
    {
        return createLocalQueryRunner();
    }

    public static LocalQueryRunner createLocalQueryRunner()
    {
        Session defaultSession = testSessionBuilder()
                .setCatalog("local")
                .setSchema(TINY_SCHEMA_NAME)
                .build();

        LocalQueryRunner localQueryRunner = new LocalQueryRunner(defaultSession);

        // add the tpch catalog
        // local queries run directly against the generator
        localQueryRunner.createCatalog(
                defaultSession.getCatalog().get(),
                new TpchConnectorFactory(1),
                ImmutableMap.of());

        localQueryRunner.getMetadata().registerBuiltInFunctions(CUSTOM_FUNCTIONS);

        SessionPropertyManager sessionPropertyManager = localQueryRunner.getMetadata().getSessionPropertyManager();
        sessionPropertyManager.addSystemSessionProperties(TEST_SYSTEM_PROPERTIES);
        sessionPropertyManager.addConnectorSessionProperties(new ConnectorId(TESTING_CATALOG), TEST_CATALOG_PROPERTIES);

        return localQueryRunner;
    }

    @Test
    public void testApplicableOptimizers()
    {
        Session session = Session.builder(getSession())
                .setSystemProperty("verbose_optimizer_info_enabled", "true")
                .build();
        String query = "SELECT o.orderkey FROM part p, orders o, lineitem l WHERE p.partkey = l.partkey AND l.orderkey = o.orderkey AND p.partkey <> o.orderkey AND p.name < l.comment";
        MaterializedResult materializedResult = computeActual(session, "explain " + query);
        String explain = (String) getOnlyElement(materializedResult.getOnlyColumnAsSet());

        checkOptimizerInfo(explain, true, ImmutableList.of("PruneCrossJoinColumns"));
        checkOptimizerInfo(explain, false, ImmutableList.of("AddNotNullFiltersToJoinNode"));

        String payloadJoinQuery = "SELECT l.* FROM (select *, map(ARRAY[1,3], ARRAY[2,4]) as m1 from lineitem) l left join orders o on (l.orderkey = o.orderkey) left join part p on (l.partkey=p.partkey)";
        materializedResult = computeActual(session, "explain " + payloadJoinQuery);
        String explainPayloadJoinQuery = (String) getOnlyElement(materializedResult.getOnlyColumnAsSet());

        checkOptimizerInfo(explainPayloadJoinQuery, false, ImmutableList.of("PayloadJoinOptimizer"));

        Session sessionWithPayload = Session.builder(session)
                .setSystemProperty(OPTIMIZE_PAYLOAD_JOINS, "true")
                .build();
        materializedResult = computeActual(sessionWithPayload, "explain " + payloadJoinQuery);
        explainPayloadJoinQuery = (String) getOnlyElement(materializedResult.getOnlyColumnAsSet());

        checkOptimizerInfo(explainPayloadJoinQuery, true, ImmutableList.of("PayloadJoinOptimizer"));
    }

    private void checkOptimizerInfo(String explain, boolean checkTriggered, List<String> optimizers)
    {
        String regex = checkTriggered ? "Triggered optimizers.*" : "Applicable optimizers.*";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(explain);
        assertTrue(matcher.find());

        String optimizerInfo = matcher.group();
        for (String opt : optimizers) {
            assertTrue(optimizerInfo.contains(opt));
        }
    }
}

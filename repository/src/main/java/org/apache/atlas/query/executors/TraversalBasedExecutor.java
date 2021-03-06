/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.query.executors;

import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.discovery.AtlasSearchResult;
import org.apache.atlas.query.AtlasDSL;
import org.apache.atlas.query.GremlinQuery;
import org.apache.atlas.query.QueryParams;
import org.apache.atlas.repository.graphdb.AtlasEdge;
import org.apache.atlas.repository.graphdb.AtlasGraph;
import org.apache.atlas.repository.graphdb.AtlasGraphTraversal;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.repository.store.graph.v2.EntityGraphRetriever;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class TraversalBasedExecutor implements DSLQueryExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(TraversalBasedExecutor.class);
    private static final String DSL_KEYWORD_LIMIT             = "limit";
    private static final String DSL_KEYWORD_OFFSET            = "offset";
    private static final String DEFAULT_LIMIT_OFFSET_TEMPLATE = " limit %d offset %d";
    private static final String CLAUSE_OFFSET_ZERO            = " offset 0";

    private final AtlasTypeRegistry     typeRegistry;
    private final AtlasGraph            graph;
    private final EntityGraphRetriever  entityRetriever;

    public TraversalBasedExecutor(AtlasTypeRegistry typeRegistry, AtlasGraph graph, EntityGraphRetriever entityRetriever) {
        this.typeRegistry    = typeRegistry;
        this.graph           = graph;
        this.entityRetriever = entityRetriever;
    }

    @Override
    public AtlasSearchResult execute(String dslQuery, int limit, int offset) throws AtlasBaseException {
        AtlasSearchResult                           ret            = new AtlasSearchResult(dslQuery, AtlasSearchResult.AtlasQueryType.DSL);
        GremlinQuery                                gremlinQuery   = toTraversal(dslQuery, limit, offset);
        AtlasGraphTraversal<AtlasVertex, AtlasEdge> graphTraversal = gremlinQuery.getTraversal();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Executing DSL: query={}, gremlinQuery={}", dslQuery, graphTraversal.toString());
        }

        List<AtlasVertex> resultList = graphTraversal.getAtlasVertexList();

        return (CollectionUtils.isNotEmpty(resultList))
                ? getSearchResult(ret, gremlinQuery, resultList)
                : getSearchResult(ret, gremlinQuery, graphTraversal.getAtlasVertexMap());
    }

    private AtlasSearchResult getSearchResult(AtlasSearchResult ret, GremlinQuery gremlinQuery, List<AtlasVertex> resultList) throws AtlasBaseException {
        return gremlinQuery.hasValidSelectClause()
                ? SelectClauseProjections.usingList(gremlinQuery, entityRetriever, resultList)
                : processVertices(ret, resultList);
    }

    private AtlasSearchResult getSearchResult(AtlasSearchResult ret, GremlinQuery gremlinQuery, Map<String, Collection<AtlasVertex>> resultMap) throws AtlasBaseException {
        if (MapUtils.isEmpty(resultMap)) {
            return ret;
        }

        if (gremlinQuery.hasValidSelectClause()) {
            return SelectClauseProjections.usingMap(gremlinQuery, entityRetriever, resultMap);
        }

        for (Collection<AtlasVertex> vertices : resultMap.values()) {
            processVertices(ret, vertices);
        }

        return ret;
    }

    private AtlasSearchResult processVertices(final AtlasSearchResult ret, final Collection<AtlasVertex> vertices) throws AtlasBaseException {
        for (AtlasVertex vertex : vertices) {
            if (vertex == null) {
                continue;
            }

            ret.addEntity(entityRetriever.toAtlasEntityHeaderWithClassifications(vertex));
        }

        return ret;
    }

    private GremlinQuery toTraversal(String query, int limit, int offset) throws AtlasBaseException {
        QueryParams params = QueryParams.getNormalizedParams(limit, offset);

        query = getStringWithLimitOffset(query, params);

        AtlasDSL.Translator dslTranslator = new AtlasDSL.Translator(query, typeRegistry, params.offset(), params.limit());
        GremlinQuery        gremlinQuery  = dslTranslator.translate();
        AtlasGraphTraversal result        = GremlinClauseToTraversalTranslator.run(this.graph, gremlinQuery.getClauses());

        gremlinQuery.setResult(result);

        return gremlinQuery;
    }

    private String getStringWithLimitOffset(String query, QueryParams params) {
        if (!query.contains(DSL_KEYWORD_LIMIT) && !query.contains(DSL_KEYWORD_OFFSET)) {
            query += String.format(DEFAULT_LIMIT_OFFSET_TEMPLATE, params.limit(), params.offset());
        }

        if (query.contains(DSL_KEYWORD_LIMIT) && !query.contains(DSL_KEYWORD_OFFSET)) {
            query += CLAUSE_OFFSET_ZERO;
        }

        return query;
    }
}

/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.common.util;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import com.vmware.admiral.service.common.MultiTenantDocument;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.QueryTask.Query;
import com.vmware.xenon.services.common.QueryTask.Query.Occurance;
import com.vmware.xenon.services.common.QueryTask.QuerySpecification.QueryOption;
import com.vmware.xenon.services.common.QueryTask.QueryTerm.MatchType;

public class QueryUtil {

    public static final long QUERY_RETRY_INTERVAL_MILLIS = Long.getLong(
            "com.vmware.admiral.common.util.query.retry.interval.millis", 500);

    public static final String GROUP_IDENTIFIER = "/groups/";

    public static QueryTask buildQuery(Class<? extends ServiceDocument> stateClass,
            boolean direct, QueryTask.Query... clauses) {
        String kind = Utils.buildKind(stateClass);
        return buildQuery(kind, direct, clauses);
    }

    public static QueryTask buildQuery(String documentKind,
            boolean direct, QueryTask.Query... clauses) {
        QueryTask q = new QueryTask();
        q.querySpec = new QueryTask.QuerySpecification();
        q.taskInfo.isDirect = direct;

        QueryTask.Query kindClause = new QueryTask.Query()
                .setTermPropertyName(ServiceDocument.FIELD_NAME_KIND)
                .setTermMatchValue(documentKind);
        q.querySpec.query.addBooleanClause(kindClause);

        for (QueryTask.Query query : clauses) {
            q.querySpec.query.addBooleanClause(query);
        }

        q.documentExpirationTimeMicros = ServiceDocumentQuery.getDefaultQueryExpiration();
        return q;
    }

    public static QueryTask buildPropertyQuery(Class<? extends ServiceDocument> stateClass,
            String... propsAndValues) {
        QueryTask.Query[] queries = new QueryTask.Query[propsAndValues.length / 2];
        for (int i = 0; i < propsAndValues.length; i++) {
            String prop = propsAndValues[i];
            String value = propsAndValues[++i];
            QueryTask.Query clause = new QueryTask.Query()
                    .setTermPropertyName(prop)
                    .setTermMatchValue(value);
            queries[(i - 1) / 2] = clause;
        }

        return buildQuery(stateClass, true, queries);
    }

    public static void addListValueClause(QueryTask q, String propName, Collection<String> values) {
        addListValueClause(q, propName, values, MatchType.TERM);
    }

    public static void addListValueClause(QueryTask q, String propName,
            Collection<String> values,
            MatchType termMatchType) {
        Query inClause = addListValueClause(propName, values, termMatchType);
        q.querySpec.query.addBooleanClause(inClause);
    }

    public static void addListValueClause(QueryTask.Query q, String propName,
            Collection<String> values,
            MatchType termMatchType) {
        QueryTask.Query inClause = addListValueClause(propName, values, termMatchType);

        q.addBooleanClause(inClause);
    }

    public static QueryTask.Query addListValueClause(String propName, Collection<String> values,
            MatchType termMatchType) {

        QueryTask.Query inClause = new QueryTask.Query();
        for (String value : values) {
            QueryTask.Query clause = new QueryTask.Query()
                    .setTermPropertyName(propName)
                    .setTermMatchValue(value)
                    .setTermMatchType(termMatchType);

            if (value.contains(GROUP_IDENTIFIER)) {
                clause.occurance = Occurance.MUST_OCCUR;
            } else {
                clause.occurance = Occurance.SHOULD_OCCUR;
            }

            inClause.addBooleanClause(clause);
            if (values.size() == 1) {
                // if we only have one value then change it to single value clause.
                inClause = clause;
                inClause.occurance = Occurance.MUST_OCCUR;
            }
        }

        return inClause;
    }

    public static void addListValueExcludeClause(QueryTask q, String propName,
            Collection<String> values) {

        for (String value : values) {
            QueryTask.Query clause = new QueryTask.Query()
                    .setTermPropertyName(propName)
                    .setTermMatchValue(value);

            clause.occurance = Occurance.MUST_NOT_OCCUR;
            q.querySpec.query.addBooleanClause(clause);
        }
    }

    public static QueryTask addExpandOption(QueryTask queryTask) {
        return addOption(queryTask, QueryOption.EXPAND_CONTENT);
    }

    public static QueryTask addCountOption(QueryTask queryTask) {
        return addOption(queryTask, QueryOption.COUNT);
    }

    public static QueryTask addBroadcastOption(QueryTask queryTask) {
        return addOption(queryTask, QueryOption.BROADCAST);
    }

    private static QueryTask addOption(QueryTask queryTask, QueryOption option) {
        if (queryTask.querySpec.options == null || queryTask.querySpec.options.isEmpty()) {
            queryTask.querySpec.options = EnumSet.of(option);
        } else {
            queryTask.querySpec.options.add(option);
        }
        return queryTask;
    }

    public static Query addGroupClauses(Query query, String propertyName, String group) {
        query.addBooleanClause(buildGroupClause(propertyName, group));
        return query;
    }

    /**
     * Create a clause to filter results by group, including global entities
     *
     * Normally a query should contain one of these clauses - one to return global results, and
     * another to return group specific results (if a group is specified)
     *
     * @param propertyName
     * @param group
     * @return
     */
    public static Query buildGroupClause(String propertyName, String group) {
        Query groupClause = new Query()
                .setTermPropertyName(propertyName);

        // if a group is not specified, search global only
        if (group == null || group.isEmpty()) {
            groupClause.setTermMatchType(MatchType.WILDCARD)
                    .setTermMatchValue(UriUtils.URI_WILDCARD_CHAR);

            groupClause.occurance = Occurance.MUST_NOT_OCCUR;

        } else {
            groupClause.setTermMatchValue(group);
            groupClause.occurance = Occurance.MUST_OCCUR;
        }

        return groupClause;
    }

    public static Query addTenantClause(String tenantLink) {
        List<String> listValues = null;
        if (tenantLink != null && !tenantLink.isEmpty()) {
            AssertUtil.assertTrue(tenantLink.startsWith(MultiTenantDocument.TENANTS_PREFIX)
                            || tenantLink.startsWith(MultiTenantDocument.USERS_PREFIX),
                    String.format("tenantLink does not have %s or %s prefix.", MultiTenantDocument.TENANTS_PREFIX,
                            MultiTenantDocument.USERS_PREFIX));
            listValues = Collections.singletonList(tenantLink);
        }

        return addTenantClause(listValues);
    }

    public static Query addTenantClause(List<String> tenantLinks) {
        Query groupClause = null;

        String propertyName = QueryTask.QuerySpecification
                .buildCollectionItemName(MultiTenantDocument.FIELD_NAME_TENANT_LINKS);

        // if a tenant is not specified, search global only
        if (tenantLinks == null || tenantLinks.isEmpty()) {
            groupClause = new Query()
                    .setTermPropertyName(propertyName);
            groupClause.setTermMatchType(MatchType.WILDCARD)
                    .setTermMatchValue(UriUtils.URI_WILDCARD_CHAR);
            groupClause.occurance = Occurance.MUST_NOT_OCCUR;

        } else {
            groupClause = addListValueClause(propertyName, tenantLinks, MatchType.TERM);
        }

        return groupClause;
    }

    public static QueryTask.Query createKindClause(Class<?> c) {
        QueryTask.Query kindClause = new QueryTask.Query()
                .setTermPropertyName(ServiceDocument.FIELD_NAME_KIND)
                .setTermMatchValue(Utils.buildKind(c));

        return kindClause;
    }

    public static QueryTask.Query createAnyPropertyClause(String query, Occurance occurence, String... propertyNames) {
        QueryTask.Query anyPropertyClause = new QueryTask.Query();

        for (String propertyName : propertyNames) {
            QueryTask.Query propClause = new QueryTask.Query()
                    .setTermPropertyName(propertyName)
                    .setTermMatchType(MatchType.WILDCARD)
                    .setTermMatchValue(query);

            propClause.occurance = occurence;
            anyPropertyClause.addBooleanClause(propClause);
        }
        return anyPropertyClause;
    }

    public static QueryTask.Query createAnyPropertyClause(String query, String... propertyNames) {

        return createAnyPropertyClause(query, Occurance.SHOULD_OCCUR, propertyNames);
    }

}

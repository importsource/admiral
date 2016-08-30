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

package com.vmware.admiral.request.allocation.filter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vmware.admiral.request.PlacementHostSelectionTaskService.PlacementHostSelectionTaskState;

/**
 * Host selection interface to be used by all filters implementing host selection algorithms
 */
public interface HostSelectionFilter extends AffinityFilter {

    /**
     * Filter the list of compute host links based on the affinity rules.
     *
     * @param state
     *            - the current placement host state task.
     * @param hostSelectionMap
     *            - the initial set of host links to be filtered from.
     * @param callback
     *            - the callback to be executed once the filtering is completed.
     */
    void filter(PlacementHostSelectionTaskState state, Map<String, HostSelection> hostSelectionMap,
            HostSelectionFilterCompletion callback);

    /**
     * Completion callback interface used when the filter is completed.
     */
    @FunctionalInterface
    public static interface HostSelectionFilterCompletion {
        void complete(Map<String, HostSelection> filteredHostSelectionMap, Throwable failure);
    }

    public static class HostSelection {
        private static final String SPLIT_REGEX = ":";
        public String hostLink;
        public int containerCount;
        public String resourcePoolLink;
        public Map<String, DescName> descNames;
        public Long availableMemory;
        public String deploymentPolicyLink;

        /** Configured location of the key-value store for the overlay networks. */
        public String clusterStore;

        public void addDesc(DescName descName) {
            if (descName == null) {
                return;
            }
            if (descNames == null) {
                descNames = new HashMap<>();
            }
            descNames.put(descName.descriptionName, descName);
        }

        public String[] mapNames(String[] names) {
            if (names == null || names.length == 0) {
                return null;
            }
            if (descNames == null) {
                return names;
            }
            String[] mappedNames = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                String[] split = names[i].split(SPLIT_REGEX, 2);
                String name = split[0];
                DescName descName = descNames.get(name);
                if (descName != null && !descName.containerNames.isEmpty()) {
                    name = descName.containerNames.iterator().next();
                    if (split.length == 2) {
                        name = name + SPLIT_REGEX + split[1];
                    }
                    mappedNames[i] = name;
                } else {
                    mappedNames[i] = names[i];
                }
            }

            return mappedNames;
        }
    }

    public static class DescName {
        public String descLink;
        public String descriptionName;
        public String[] affinities;
        private Set<String> containerNames;

        public void addContainerNames(List<String> names) {
            if (names == null) {
                return;
            }
            if (containerNames == null) {
                containerNames = new HashSet<>();
            }
            containerNames.addAll(names);
        }
    }

    public static class HostSelectionFilterException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public HostSelectionFilterException(String s) {
            super(s);
        }
    }
}

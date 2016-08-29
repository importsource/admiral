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

import Search from 'components/common/Search';

var VueSearch = Vue.extend({
  template: '<div></div>',
  props: {
    queryOptions: {},
    placeholder: {type: String},
    placeholderByCategory: {},
    suggestionProperties: {type: Array},
    occurrenceProperties: {type: Array}
  },
  ready: function() {
    var searchProperties = {
      suggestionProperties: this.suggestionProperties,
      occurrences: this.occurrenceProperties,
      placeholderHint: this.placeholder,
      placeholderHintByCategory: this.placeholderByCategory
    };

    this.search = new Search(searchProperties, () => {
      this.$dispatch('search-change', this.getQueryOptions());
    });
    if (this.queryOptions) {
      this.search.setQueryOptions(this.queryOptions);
    }
    $(this.$el).append(this.search.getEl());
  },

  attached: function() {
    this.queryUnwatch = this.$watch('queryOptions',
      (newQueryOptions) => this.search.setQueryOptions(newQueryOptions));
  },

  detached: function() {
    this.queryUnwatch();
  },

  methods: {
    getQueryOptions: function() {
      return this.search.getQueryOptions();
    }
  }
});

Vue.component('search', VueSearch);

export default VueSearch;

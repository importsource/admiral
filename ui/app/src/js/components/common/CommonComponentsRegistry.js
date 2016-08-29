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

/**
 * Helper class that imports The common Vue components so they can be used without imports in other
 * Vue components. Internally when components are imported for the first time they register as Vue
 * components.
 * */

/* eslint-disable */

import VueSearch from 'components/common/VueSearch';
import VueRefreshButton from 'components/common/VueRefreshButton';
import ListTitle from 'components/common/ListTitle';
import VueTitleActionButton from 'components/common/VueTitleActionButton';
import VueMulticolumnInputs from 'components/common/VueMulticolumnInputs';
import VueActionButton from 'components/common/VueActionButton';
import VueTableHeaderSort from 'components/common/VueTableHeaderSort';
import VueGrid from 'components/common/VueGrid';
import VueAlert from 'components/common/VueAlert';
import ContextSidePanelToolbarItem from 'components/ContextSidePanelToolbarItem';
import ContextSidePanel from 'components/ContextSidePanel';

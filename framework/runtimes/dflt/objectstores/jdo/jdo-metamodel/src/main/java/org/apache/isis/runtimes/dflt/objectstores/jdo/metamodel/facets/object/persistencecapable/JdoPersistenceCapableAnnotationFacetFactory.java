/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.isis.runtimes.dflt.objectstores.jdo.metamodel.facets.object.persistencecapable;


import javax.jdo.annotations.IdentityType;
import javax.jdo.annotations.PersistenceCapable;

import org.apache.isis.core.commons.lang.StringUtils;
import org.apache.isis.core.metamodel.facetapi.FacetUtil;
import org.apache.isis.core.metamodel.facetapi.FeatureType;
import org.apache.isis.core.metamodel.facets.AnnotationBasedFacetFactoryAbstract;


public class JdoPersistenceCapableAnnotationFacetFactory extends
        AnnotationBasedFacetFactoryAbstract {

    public JdoPersistenceCapableAnnotationFacetFactory() {
        super(FeatureType.OBJECTS_ONLY);
    }

    @Override
    public void process(ProcessClassContext processClassContext) {
        final Class<?> cls = processClassContext.getCls();
        final PersistenceCapable annotation = getAnnotation(cls, PersistenceCapable.class);
        if (annotation == null) {
            return;
        }
        String annotationTableAttribute = annotation.table();
        if (StringUtils.isNullOrEmpty(annotationTableAttribute)) {
            annotationTableAttribute = cls.getSimpleName();
        }

        final IdentityType annotationIdentityType = annotation.identityType();
        
        FacetUtil.addFacet(new JdoPersistenceCapableFacetAnnotation(
                annotationTableAttribute, annotationIdentityType, processClassContext.getFacetHolder()));
        return;
    }


}

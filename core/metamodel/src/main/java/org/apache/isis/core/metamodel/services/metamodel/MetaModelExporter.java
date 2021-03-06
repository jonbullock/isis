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
package org.apache.isis.core.metamodel.services.metamodel;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.isis.applib.annotation.NatureOfService;
import org.apache.isis.applib.annotation.PublishedAction;
import org.apache.isis.applib.annotation.PublishedObject;
import org.apache.isis.applib.services.command.CommandDtoProcessor;
import org.apache.isis.applib.services.metamodel.MetaModelService6;
import org.apache.isis.applib.spec.Specification;
import org.apache.isis.core.metamodel.facetapi.Facet;
import org.apache.isis.core.metamodel.facetapi.FacetHolder;
import org.apache.isis.core.metamodel.facets.object.domainservice.DomainServiceFacet;
import org.apache.isis.core.metamodel.spec.ObjectSpecification;
import org.apache.isis.core.metamodel.spec.feature.Contributed;
import org.apache.isis.core.metamodel.spec.feature.ObjectAction;
import org.apache.isis.core.metamodel.spec.feature.ObjectActionParameter;
import org.apache.isis.core.metamodel.spec.feature.ObjectAssociation;
import org.apache.isis.core.metamodel.spec.feature.ObjectMember;
import org.apache.isis.core.metamodel.spec.feature.OneToManyAssociation;
import org.apache.isis.core.metamodel.spec.feature.OneToOneActionParameter;
import org.apache.isis.core.metamodel.spec.feature.OneToOneAssociation;
import org.apache.isis.core.metamodel.specloader.SpecificationLoader;
import org.apache.isis.objectstore.jdo.metamodel.facets.object.query.JdoNamedQuery;
import org.apache.isis.schema.metamodel.v1.Action;
import org.apache.isis.schema.metamodel.v1.Collection;
import org.apache.isis.schema.metamodel.v1.DomainClassDto;
import org.apache.isis.schema.metamodel.v1.FacetAttr;
import org.apache.isis.schema.metamodel.v1.Member;
import org.apache.isis.schema.metamodel.v1.MetamodelDto;
import org.apache.isis.schema.metamodel.v1.Param;
import org.apache.isis.schema.metamodel.v1.Property;
import org.apache.isis.schema.metamodel.v1.ScalarParam;
import org.apache.isis.schema.metamodel.v1.VectorParam;
import org.apache.isis.schema.utils.CommonDtoUtils;

class MetaModelExporter {

    @SuppressWarnings("unused")
    private final static Logger LOG = LoggerFactory.getLogger(MetaModelExporter.class);

    SpecificationLoader specificationLookup;

    public MetaModelExporter(final SpecificationLoader specificationLookup) {
        this.specificationLookup = specificationLookup;
    }

    /**
     * The metamodel is populated in two phases, first to create a {@link DomainClassDto} for each ObjectSpecification,
     * and then to populate the members of those domain class types.
     *
     * <p>
     *     This is because the members (and action parameters) all reference the {@link DomainClassDto}s, so these need
     *     to exist first.
     * </p>
     */
    MetamodelDto exportMetaModel(final MetaModelService6.Config config) {
        MetamodelDto metamodelDto = new MetamodelDto();

        // phase 1: create a domainClassType for each ObjectSpecification
        // these are added into a map for lookups in phase 2
        final Map<ObjectSpecification, DomainClassDto> domainClassByObjectSpec = Maps.newHashMap();
        for (final ObjectSpecification specification : specificationLookup.allSpecifications()) {
            if(notInPackagePrefixes(specification, config)) {
                continue;
            }
            if(config.isIgnoreMixins() && specification.isMixin()) {
                continue;
            }
            if(config.isIgnoreInterfaces() && specification.getCorrespondingClass().isInterface()) {
                continue;
            }
            if(config.isIgnoreAbstractClasses() && Modifier.isAbstract(specification.getCorrespondingClass().getModifiers())) {
                continue;
            }
            if(config.isIgnoreBuiltInValueTypes() && isValueType(specification)) {
                continue;
            }

            DomainClassDto domainClassType = asXsdType(specification, config);
            domainClassByObjectSpec.put(specification, domainClassType);
        }

        // phase 2: now flesh out the domain class types, passing the map for lookups of the domainClassTypes that
        // correspond to each object members types.
        for (final ObjectSpecification specification : Lists.newArrayList(domainClassByObjectSpec.keySet())) {
            addFacetsAndMembersTo(specification, domainClassByObjectSpec, config);
        }

        // phase 2.5: check no duplicates
        final Map<String, ObjectSpecification> objectSpecificationByDomainClassId = Maps.newHashMap();
        final List<String> buf = Lists.newArrayList();
        for (final Map.Entry<ObjectSpecification, DomainClassDto> entry : domainClassByObjectSpec.entrySet()) {
            final ObjectSpecification objectSpecification = entry.getKey();
            final DomainClassDto domainClassDto = entry.getValue();
            final String id = domainClassDto.getId();
            final ObjectSpecification existing = objectSpecificationByDomainClassId.get(id);
            if(existing != null) {
                if(!existing.getCorrespondingClass().isEnum()) {
                    buf.add(String.format("%s mapped to %s and %s", id, existing, objectSpecification));
                }
            } else {
                objectSpecificationByDomainClassId.put(id, objectSpecification);
            }
        }
        if(buf.size() > 0) {
            throw new IllegalStateException(Joiner.on("\n").join(buf));
        }

        // phase 3: now copy all domain classes into the metamodel
        for (final DomainClassDto domainClass : Lists.newArrayList(domainClassByObjectSpec.values())) {
            metamodelDto.getDomainClassDto().add(domainClass);
        }

        sortDomainClasses(metamodelDto.getDomainClassDto());

        return metamodelDto;
    }

    private boolean notInPackagePrefixes(
            final ObjectSpecification specification, final MetaModelService6.Config config) {
        return !inPackagePrefixes(specification, config);
    }

    private boolean inPackagePrefixes(
            final ObjectSpecification specification,
            final MetaModelService6.Config config) {
        final String canonicalName = specification.getCorrespondingClass().getCanonicalName();
        for (final String s : config.getPackagePrefixes()) {
            if(canonicalName.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private DomainClassDto asXsdType(
            final ObjectSpecification specification,
            final MetaModelService6.Config config) {

        final DomainClassDto domainClass = new DomainClassDto();

        domainClass.setId(specification.getFullIdentifier());

        if(specification.isService()) {
            domainClass.setService(true);
        }

        return domainClass;
    }

    private void addFacetsAndMembersTo(
            final ObjectSpecification specification,
            final Map<ObjectSpecification, DomainClassDto> domainClassByObjectSpec,
            final MetaModelService6.Config config) {

        final DomainClassDto domainClass = lookupDomainClass(specification, domainClassByObjectSpec, config);
        if(domainClass.getFacets() == null) {
            domainClass.setFacets(new org.apache.isis.schema.metamodel.v1.FacetHolder.Facets());
        }
        addFacets(specification, domainClass.getFacets(), config);

        if(specification.isValueOrIsParented() || isEnum(specification)) {
            return;
        }

        if (specification.isService()) {
            if(!hasNatureOfServiceOfDomain(specification)) {
                addActions(specification, domainClassByObjectSpec, config);
            }
        } else {
            addProperties(specification, domainClassByObjectSpec, config);
            addCollections(specification, domainClassByObjectSpec, config);
            addActions(specification, domainClassByObjectSpec, config);
        }
    }

    private boolean isEnum(final ObjectSpecification specification) {
        return specification.getCorrespondingClass().isEnum();
    }

    private boolean hasNatureOfServiceOfDomain(final ObjectSpecification specification) {
        final DomainServiceFacet domainServiceFacet = specification.getFacet(DomainServiceFacet.class);
        return domainServiceFacet != null && domainServiceFacet.getNatureOfService() == NatureOfService.DOMAIN;
    }

    private void addProperties(
            final ObjectSpecification specification,
            final Map<ObjectSpecification, DomainClassDto> domainClassByObjectSpec,
            final MetaModelService6.Config config) {
        final DomainClassDto domainClass = lookupDomainClass(specification, domainClassByObjectSpec, config);

        final List<ObjectAssociation> oneToOneAssociations =
                specification.getAssociations(Contributed.INCLUDED, ObjectAssociation.Filters.PROPERTIES);
        if(domainClass.getProperties() == null) {
            domainClass.setProperties(new DomainClassDto.Properties());
        }
        final List<Property> properties = domainClass.getProperties().getProp();
        for (final ObjectAssociation association : oneToOneAssociations) {
            final OneToOneAssociation otoa = (OneToOneAssociation) association;
            properties.add(asXsdType(otoa, domainClassByObjectSpec, config));
        }
        sortMembers(properties);
    }

    private void addCollections(
            final ObjectSpecification specification,
            final Map<ObjectSpecification, DomainClassDto> domainClassByObjectSpec,
            final MetaModelService6.Config config) {
        final DomainClassDto domainClass = lookupDomainClass(specification, domainClassByObjectSpec, config);
        final List<ObjectAssociation> oneToManyAssociations =
                specification.getAssociations(Contributed.INCLUDED, ObjectAssociation.Filters.COLLECTIONS);
        if(domainClass.getCollections() == null) {
            domainClass.setCollections(new DomainClassDto.Collections());
        }
        final List<Collection> collections = domainClass.getCollections().getColl();
        for (final ObjectAssociation association : oneToManyAssociations) {
            final OneToManyAssociation otma = (OneToManyAssociation) association;
            collections.add(asXsdType(otma, domainClassByObjectSpec, config));
        }
        sortMembers(collections);
    }

    private void addActions(
            final ObjectSpecification specification,
            final Map<ObjectSpecification, DomainClassDto> domainClassByObjectSpec,
            final MetaModelService6.Config config) {
        final DomainClassDto domainClass = lookupDomainClass(specification, domainClassByObjectSpec, config);
        final List<ObjectAction> objectActions =
                specification.getObjectActions(Contributed.INCLUDED);
        if(domainClass.getActions() == null) {
            domainClass.setActions(new DomainClassDto.Actions());
        }
        final List<Action> actions = domainClass.getActions().getAct();
        for (final ObjectAction action : objectActions) {
            actions.add(asXsdType(action, domainClassByObjectSpec, config));
        }
        sortMembers(actions);
    }

    private Property asXsdType(
            final OneToOneAssociation otoa,
            final Map<ObjectSpecification, DomainClassDto> domainClassByObjectSpec,
            final MetaModelService6.Config config) {

        Property propertyType = new Property();
        propertyType.setId(otoa.getId());
        propertyType.setFacets(new org.apache.isis.schema.metamodel.v1.FacetHolder.Facets());
        final ObjectSpecification specification = otoa.getSpecification();
        final DomainClassDto value = lookupDomainClass(specification, domainClassByObjectSpec, config);
        propertyType.setType(value);

        addFacets(otoa, propertyType.getFacets(), config);
        return propertyType;
    }

    private Collection asXsdType(
            final OneToManyAssociation otoa,
            final Map<ObjectSpecification, DomainClassDto> domainClassByObjectSpec,
            final MetaModelService6.Config config) {
        Collection collectionType = new Collection();
        collectionType.setId(otoa.getId());
        collectionType.setFacets(new org.apache.isis.schema.metamodel.v1.FacetHolder.Facets());
        final ObjectSpecification specification = otoa.getSpecification();
        final DomainClassDto value = lookupDomainClass(specification, domainClassByObjectSpec, config);
        collectionType.setType(value);

        addFacets(otoa, collectionType.getFacets(), config);
        return collectionType;
    }

    private Action asXsdType(
            final ObjectAction oa,
            final Map<ObjectSpecification, DomainClassDto> domainClassByObjectSpec,
            final MetaModelService6.Config config) {
        Action actionType = new Action();
        actionType.setId(oa.getId());
        actionType.setFacets(new org.apache.isis.schema.metamodel.v1.FacetHolder.Facets());
        actionType.setParams(new Action.Params());

        final ObjectSpecification specification = oa.getReturnType();
        final DomainClassDto value = lookupDomainClass(specification, domainClassByObjectSpec, config);
        actionType.setReturnType(value);

        addFacets(oa, actionType.getFacets(), config);

        final List<ObjectActionParameter> parameters = oa.getParameters();
        final List<Param> params = actionType.getParams().getParam();
        for (final ObjectActionParameter parameter : parameters) {
            params.add(asXsdType(parameter, domainClassByObjectSpec, config));
        }
        return actionType;
    }

    private DomainClassDto lookupDomainClass(
            final ObjectSpecification specification,
            final Map<ObjectSpecification, DomainClassDto> domainClassByObjectSpec,
            final MetaModelService6.Config config) {
        DomainClassDto value = domainClassByObjectSpec.get(specification);
        if(value == null) {
            final DomainClassDto domainClass = asXsdType(specification, config);
            domainClassByObjectSpec.put(specification, domainClass);
            value = domainClass;
        }
        return value;
    }

    private Param asXsdType(
            final ObjectActionParameter parameter,
            final Map<ObjectSpecification, DomainClassDto> domainClassByObjectSpec,
            final MetaModelService6.Config config) {

        Param parameterType = parameter instanceof OneToOneActionParameter
                                    ? new ScalarParam()
                                    : new VectorParam();
        parameterType.setId(parameter.getId());
        parameterType.setFacets(new org.apache.isis.schema.metamodel.v1.FacetHolder.Facets());

        final ObjectSpecification specification = parameter.getSpecification();
        final DomainClassDto value = lookupDomainClass(specification, domainClassByObjectSpec, config);
        parameterType.setType(value);

        addFacets(parameter, parameterType.getFacets(), config);
        return parameterType;
    }

    private void addFacets(
            final FacetHolder facetHolder,
            final org.apache.isis.schema.metamodel.v1.FacetHolder.Facets facets,
            final MetaModelService6.Config config) {

        final Class<? extends Facet>[] facetTypes = facetHolder.getFacetTypes();
        for (final Class<? extends Facet> facetType : facetTypes) {
            final Facet facet = facetHolder.getFacet(facetType);
            if(!facet.isNoop() || !config.isIgnoreNoop()) {
                facets.getFacet().add(asXsdType(facet, config));
            }
        }
        sortFacets(facets.getFacet());
    }

    private org.apache.isis.schema.metamodel.v1.Facet asXsdType(
            final Facet facet,
            final MetaModelService6.Config config) {
        final org.apache.isis.schema.metamodel.v1.Facet facetType = new org.apache.isis.schema.metamodel.v1.Facet();
        facetType.setId(facet.facetType().getCanonicalName());
        facetType.setFqcn(facet.getClass().getCanonicalName());

        addFacetAttributes(facet, facetType, config);

        return facetType;
    }

    private void addFacetAttributes(
            final Facet facet,
            final org.apache.isis.schema.metamodel.v1.Facet facetType,
            final MetaModelService6.Config config) {

        Map<String, Object> attributeMap = Maps.newTreeMap();
        facet.appendAttributesTo(attributeMap);

        for (final String key : attributeMap.keySet()) {
            Object attributeObj = attributeMap.get(key);
            if(attributeObj == null) {
                continue;
            }

            String str = asStr(attributeObj);
            addAttribute(facetType,key, str);
        }

        sortFacetAttributes(facetType.getAttr());
    }

    private void addAttribute(
            final org.apache.isis.schema.metamodel.v1.Facet facetType,
            final String key, final String str) {
        if(str == null) {
            return;
        }
        FacetAttr attributeDto = new FacetAttr();
        attributeDto.setName(key);
        attributeDto.setValue(str);
        facetType.getAttr().add(attributeDto);
    }

    private void sortFacetAttributes(final List<FacetAttr> attributes) {
        Collections.sort(attributes, new Comparator<FacetAttr>() {
            @Override
            public int compare(final FacetAttr o1, final FacetAttr o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });
    }

    private static void sortDomainClasses(final List<DomainClassDto> specifications) {
        Collections.sort(specifications, new Comparator<DomainClassDto>() {
            @Override
            public int compare(final DomainClassDto o1, final DomainClassDto o2) {
                return o1.getId().compareTo(o2.getId());
            }
        });
    }

    private void sortMembers(final List<? extends Member> members) {
        Collections.sort(members, new Comparator<Member>() {
            @Override public int compare(final Member o1, final Member o2) {
                return o1.getId().compareTo(o2.getId());
            }
        });
    }

    private void sortFacets(final List<org.apache.isis.schema.metamodel.v1.Facet> facets) {
        Collections.sort(facets, new Comparator<org.apache.isis.schema.metamodel.v1.Facet>() {
            @Override public int compare(final org.apache.isis.schema.metamodel.v1.Facet o1, final org.apache.isis.schema.metamodel.v1.Facet o2) {
                return o1.getId().compareTo(o2.getId());
            }
        });
    }

    private boolean isValueType(final ObjectSpecification specification) {
        return CommonDtoUtils.VALUE_TYPES.contains(specification.getCorrespondingClass());
    }


    private String asStr(final Object attributeObj) {
        String str;
        if(attributeObj instanceof Method) {
            str = asStr((Method) attributeObj);
        } else if(attributeObj instanceof String) {
            str = asStr((String) attributeObj);
        } else if(attributeObj instanceof Enum) {
            str = asStr((Enum) attributeObj);
        } else if(attributeObj instanceof Class) {
            str = asStr((Class) attributeObj);
        } else if(attributeObj instanceof Specification) {
            str = asStr((Specification) attributeObj);
        } else if(attributeObj instanceof Facet) {
            str = asStr((Facet) attributeObj);
        } else if(attributeObj instanceof JdoNamedQuery) {
            str = asStr((JdoNamedQuery) attributeObj);
        } else if(attributeObj instanceof Pattern) {
            str = asStr((Pattern) attributeObj);
        } else if(attributeObj instanceof PublishedObject.PayloadFactory) {
            str = asStr((PublishedObject.PayloadFactory) attributeObj);
        } else if(attributeObj instanceof PublishedAction.PayloadFactory) {
            str = asStr((PublishedAction.PayloadFactory) attributeObj);
        } else if(attributeObj instanceof CommandDtoProcessor) {
            str = asStr((CommandDtoProcessor) attributeObj);
        } else if(attributeObj instanceof ObjectSpecification) {
            str = asStr((ObjectSpecification) attributeObj);
        } else if(attributeObj instanceof ObjectMember) {
            str = asStr((ObjectMember) attributeObj);
        } else if(attributeObj instanceof List) {
            str = asStr((List<?>) attributeObj);
        } else if(attributeObj instanceof Object[]) {
            str = asStr((Object[]) attributeObj);
        } else  {
            str = "" + attributeObj;
        }
        return str;
    }

    private String asStr(final String attributeObj) {
        return Strings.emptyToNull(attributeObj);
    }

    private String asStr(final Specification attributeObj) {
        return attributeObj.getClass().getName();
    }

    private String asStr(final ObjectSpecification attributeObj) {
        return attributeObj.getFullIdentifier();
    }

    private String asStr(final JdoNamedQuery attributeObj) {
        return attributeObj.getName();
    }

    private String asStr(final CommandDtoProcessor attributeObj) {
        return attributeObj.getClass().getName();
    }

    private String asStr(final PublishedAction.PayloadFactory attributeObj) {
        return attributeObj.getClass().getName();
    }

    private String asStr(final PublishedObject.PayloadFactory attributeObj) {
        return attributeObj.getClass().getName();
    }

    private String asStr(final Pattern attributeObj) {
        return attributeObj.pattern();
    }

    private String asStr(final Facet attributeObj) {
        return attributeObj.getClass().getName();
    }

    private String asStr(final ObjectMember attributeObj) {
        return attributeObj.getId();
    }

    private String asStr(final Class attributeObj) {
        return attributeObj.getCanonicalName();
    }

    private String asStr(final Enum attributeObj) {
        return attributeObj.name();
    }

    private String asStr(final Method attributeObj) {
        return attributeObj.toGenericString();
    }

    private String asStr(final Object[] list) {
        if(list.length == 0) {
            return null; // skip
        }
        List<String> strings = Lists.newArrayList();
        for (final Object o : list) {
            String s = asStr(o);
            strings.add(s);
        }
        return Joiner.on(";").join(strings);
    }

    private String asStr(final List<?> list) {
        if(list.isEmpty()) {
            return null; // skip
        }
        List<String> strings = Lists.newArrayList();
        for (final Object o : list) {
            String s = asStr(o);
            strings.add(s);
        }
        return Joiner.on(";").join(strings);
    }



}

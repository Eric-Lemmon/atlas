/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.repository.converters;

import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.AtlasException;
import org.apache.atlas.CreateUpdateEntitiesResult;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.TypeCategory;
import org.apache.atlas.model.instance.AtlasClassification;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntitiesWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.EntityMutationResponse;
import org.apache.atlas.model.instance.EntityMutations.EntityOperation;
import org.apache.atlas.model.instance.GuidMapping;
import org.apache.atlas.model.legacy.EntityResult;
import org.apache.atlas.v1.model.instance.Referenceable;
import org.apache.atlas.v1.model.instance.Struct;
import org.apache.atlas.repository.converters.AtlasFormatConverter.ConverterContext;
import org.apache.atlas.type.AtlasClassificationType;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Singleton
@Component
public class AtlasInstanceConverter {
    private static final Logger LOG = LoggerFactory.getLogger(AtlasInstanceConverter.class);

    private final AtlasTypeRegistry     typeRegistry;
    private final AtlasFormatConverters instanceFormatters;

    @Inject
    public AtlasInstanceConverter(AtlasTypeRegistry typeRegistry, AtlasFormatConverters instanceFormatters) {
        this.typeRegistry       = typeRegistry;
        this.instanceFormatters = instanceFormatters;
    }

    public Referenceable[] getReferenceables(Collection<AtlasEntity> entities) throws AtlasBaseException {
        Referenceable[] ret = new Referenceable[entities.size()];

        AtlasFormatConverter.ConverterContext ctx = new AtlasFormatConverter.ConverterContext();

        for(Iterator<AtlasEntity> i = entities.iterator(); i.hasNext(); ) {
            ctx.addEntity(i.next());
        }

        Iterator<AtlasEntity> entityIterator = entities.iterator();
        for (int i = 0; i < entities.size(); i++) {
            ret[i] = getReferenceable(entityIterator.next(), ctx);
        }

        return ret;
    }

    public Referenceable getReferenceable(AtlasEntity entity) throws AtlasBaseException {
        return getReferenceable(entity, new ConverterContext());
    }

    public Referenceable getReferenceable(AtlasEntity.AtlasEntityWithExtInfo entity) throws AtlasBaseException {
        AtlasFormatConverter.ConverterContext ctx = new AtlasFormatConverter.ConverterContext();

        ctx.addEntity(entity.getEntity());
        for(Map.Entry<String, AtlasEntity> entry : entity.getReferredEntities().entrySet()) {
            ctx.addEntity(entry.getValue());
        }

        return getReferenceable(entity.getEntity(), ctx);
    }

    public Referenceable getReferenceable(AtlasEntity entity, final ConverterContext ctx) throws AtlasBaseException {
        AtlasFormatConverter converter  = instanceFormatters.getConverter(TypeCategory.ENTITY);
        AtlasType            entityType = typeRegistry.getType(entity.getTypeName());
        Referenceable        ref        = (Referenceable) converter.fromV2ToV1(entity, entityType, ctx);

        return ref;
    }

    public Struct getTrait(AtlasClassification classification) throws AtlasBaseException {
        AtlasFormatConverter converter          = instanceFormatters.getConverter(TypeCategory.CLASSIFICATION);
        AtlasType            classificationType = typeRegistry.getType(classification.getTypeName());
        Struct               trait               = (Struct)converter.fromV2ToV1(classification, classificationType, new ConverterContext());

        return trait;
    }

    public AtlasClassification toAtlasClassification(Struct classification) throws AtlasBaseException {
        AtlasFormatConverter    converter          = instanceFormatters.getConverter(TypeCategory.CLASSIFICATION);
        AtlasClassificationType classificationType = typeRegistry.getClassificationTypeByName(classification.getTypeName());

        if (classificationType == null) {
            throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, TypeCategory.CLASSIFICATION.name(), classification.getTypeName());
        }

        AtlasClassification  ret = (AtlasClassification)converter.fromV1ToV2(classification, classificationType, new AtlasFormatConverter.ConverterContext());

        return ret;
    }

    public AtlasEntitiesWithExtInfo toAtlasEntity(Referenceable referenceable) throws AtlasBaseException {
        AtlasEntityFormatConverter converter  = (AtlasEntityFormatConverter) instanceFormatters.getConverter(TypeCategory.ENTITY);
        AtlasEntityType            entityType = typeRegistry.getEntityTypeByName(referenceable.getTypeName());

        if (entityType == null) {
            throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, TypeCategory.ENTITY.name(), referenceable.getTypeName());
        }

        ConverterContext ctx    = new ConverterContext();
        AtlasEntity      entity = converter.fromV1ToV2(referenceable, entityType, ctx);

        ctx.addEntity(entity);

        return ctx.getEntities();
    }


    public AtlasEntity.AtlasEntitiesWithExtInfo toAtlasEntities(List<Referenceable> referenceables) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> toAtlasEntities({})", referenceables);
        }

        AtlasFormatConverter.ConverterContext context = new AtlasFormatConverter.ConverterContext();

        for (Referenceable referenceable : referenceables) {
            AtlasEntity entity = fromV1toV2Entity(referenceable, context);

            context.addEntity(entity);
        }

        AtlasEntity.AtlasEntitiesWithExtInfo ret = context.getEntities();

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== toAtlasEntities({}): ret=", referenceables, ret);
        }

        return ret;
    }

    public AtlasEntitiesWithExtInfo toAtlasEntities(String[] jsonEntities) throws AtlasBaseException, AtlasException {
        Referenceable[] referenceables = new Referenceable[jsonEntities.length];

        for (int i = 0; i < jsonEntities.length; i++) {
            referenceables[i] = AtlasType.fromV1Json(jsonEntities[i], Referenceable.class);
        }

        AtlasEntityFormatConverter converter = (AtlasEntityFormatConverter) instanceFormatters.getConverter(TypeCategory.ENTITY);
        ConverterContext           context   = new ConverterContext();

        for (Referenceable referenceable : referenceables) {
            AtlasEntityType entityType = typeRegistry.getEntityTypeByName(referenceable.getTypeName());

            if (entityType == null) {
                throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, TypeCategory.ENTITY.name(), referenceable.getTypeName());
            }

            AtlasEntity entity = converter.fromV1ToV2(referenceable, entityType, context);

            context.addEntity(entity);
        }

        AtlasEntitiesWithExtInfo ret = context.getEntities();

        return ret;
    }

    private AtlasEntity fromV1toV2Entity(Referenceable referenceable, AtlasFormatConverter.ConverterContext context) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> fromV1toV2Entity({})", referenceable);
        }

        AtlasEntityFormatConverter converter = (AtlasEntityFormatConverter) instanceFormatters.getConverter(TypeCategory.ENTITY);

        AtlasEntity entity = converter.fromV1ToV2(referenceable, typeRegistry.getType(referenceable.getTypeName()), context);

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== fromV1toV2Entity({}): {}", referenceable, entity);
        }

        return entity;
    }

    public CreateUpdateEntitiesResult toCreateUpdateEntitiesResult(EntityMutationResponse reponse) {
        CreateUpdateEntitiesResult ret = null;

        if (reponse != null) {
            Map<EntityOperation, List<AtlasEntityHeader>> mutatedEntities = reponse.getMutatedEntities();
            Map<String, String>                           guidAssignments = reponse.getGuidAssignments();

            ret = new CreateUpdateEntitiesResult();

            if (MapUtils.isNotEmpty(guidAssignments)) {
                ret.setGuidMapping(new GuidMapping(guidAssignments));
            }

            if (MapUtils.isNotEmpty(mutatedEntities)) {
                EntityResult entityResult = new EntityResult();

                for (Map.Entry<EntityOperation, List<AtlasEntityHeader>> e : mutatedEntities.entrySet()) {
                    switch (e.getKey()) {
                        case CREATE:
                            List<AtlasEntityHeader> createdEntities = mutatedEntities.get(EntityOperation.CREATE);
                            if (CollectionUtils.isNotEmpty(createdEntities)) {
                                Collections.reverse(createdEntities);
                                entityResult.set(EntityResult.OP_CREATED, getGuids(createdEntities));
                            }
                            break;
                        case UPDATE:
                            List<AtlasEntityHeader> updatedEntities = mutatedEntities.get(EntityOperation.UPDATE);
                            if (CollectionUtils.isNotEmpty(updatedEntities)) {
                                Collections.reverse(updatedEntities);
                                entityResult.set(EntityResult.OP_UPDATED, getGuids(updatedEntities));
                            }
                            break;
                        case PARTIAL_UPDATE:
                            List<AtlasEntityHeader> partialUpdatedEntities = mutatedEntities.get(EntityOperation.PARTIAL_UPDATE);
                            if (CollectionUtils.isNotEmpty(partialUpdatedEntities)) {
                                Collections.reverse(partialUpdatedEntities);
                                entityResult.set(EntityResult.OP_UPDATED, getGuids(partialUpdatedEntities));
                            }
                            break;
                        case DELETE:
                            List<AtlasEntityHeader> deletedEntities = mutatedEntities.get(EntityOperation.DELETE);
                            if (CollectionUtils.isNotEmpty(deletedEntities)) {
                                Collections.reverse(deletedEntities);
                                entityResult.set(EntityResult.OP_DELETED, getGuids(deletedEntities));
                            }
                            break;
                    }

                }

                ret.setEntityResult(entityResult);
            }
        }

        return ret;
    }

    public List<String> getGuids(List<AtlasEntityHeader> entities) {
        List<String> ret = null;

        if (CollectionUtils.isNotEmpty(entities)) {
            ret = new ArrayList<>();
            for (AtlasEntityHeader entity : entities) {
                ret.add(entity.getGuid());
            }
        }

        return ret;
    }
}

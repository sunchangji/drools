/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.dmn.core.compiler;

import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import org.kie.api.io.Resource;
import org.kie.dmn.api.core.DMNCompiler;
import org.kie.dmn.api.core.DMNCompilerConfiguration;
import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNType;
import org.kie.dmn.api.core.ast.BusinessKnowledgeModelNode;
import org.kie.dmn.api.core.ast.DMNNode;
import org.kie.dmn.api.core.ast.DecisionNode;
import org.kie.dmn.api.core.ast.InputDataNode;
import org.kie.dmn.api.core.ast.ItemDefNode;
import org.kie.dmn.api.marshalling.v1_1.DMNExtensionRegister;
import org.kie.dmn.api.marshalling.v1_1.DMNMarshaller;
import org.kie.dmn.backend.marshalling.v1_1.DMNMarshallerFactory;
import org.kie.dmn.core.api.DMNFactory;
import org.kie.dmn.core.ast.BusinessKnowledgeModelNodeImpl;
import org.kie.dmn.core.ast.DMNBaseNode;
import org.kie.dmn.core.ast.DecisionNodeImpl;
import org.kie.dmn.core.ast.ItemDefNodeImpl;
import org.kie.dmn.core.compiler.ImportDMNResolverUtil.ImportType;
import org.kie.dmn.core.impl.BaseDMNTypeImpl;
import org.kie.dmn.core.impl.CompositeTypeImpl;
import org.kie.dmn.core.impl.DMNModelImpl;
import org.kie.dmn.core.util.Msg;
import org.kie.dmn.core.util.MsgUtil;
import org.kie.dmn.feel.lang.Type;
import org.kie.dmn.feel.lang.types.AliasFEELType;
import org.kie.dmn.feel.lang.types.BuiltInType;
import org.kie.dmn.feel.runtime.UnaryTest;
import org.kie.dmn.feel.util.Either;
import org.kie.dmn.model.v1_1.DMNElementReference;
import org.kie.dmn.model.v1_1.DMNModelInstrumentedBase;
import org.kie.dmn.model.v1_1.DRGElement;
import org.kie.dmn.model.v1_1.Decision;
import org.kie.dmn.model.v1_1.DecisionTable;
import org.kie.dmn.model.v1_1.Definitions;
import org.kie.dmn.model.v1_1.Import;
import org.kie.dmn.model.v1_1.InformationRequirement;
import org.kie.dmn.model.v1_1.ItemDefinition;
import org.kie.dmn.model.v1_1.KnowledgeRequirement;
import org.kie.dmn.model.v1_1.NamedElement;
import org.kie.dmn.model.v1_1.OutputClause;
import org.kie.dmn.model.v1_1.UnaryTests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DMNCompilerImpl
        implements DMNCompiler {

    private static final Logger logger = LoggerFactory.getLogger( DMNCompilerImpl.class );
    private final DMNEvaluatorCompiler evaluatorCompiler;
    private final DMNFEELHelper feel;
    private DMNCompilerConfiguration dmnCompilerConfig;
    private Deque<DRGElementCompiler> drgCompilers = new LinkedList<>();
    {
        drgCompilers.add( new InputDataCompiler() );
        drgCompilers.add( new BusinessKnowledgeModelCompiler() );
        drgCompilers.add( new DecisionCompiler() );
        drgCompilers.add( new KnowledgeSourceCompiler() ); // keep last as it's a void compiler
    }

    public DMNCompilerImpl() {
        this(DMNFactory.newCompilerConfiguration());
    }

    public DMNCompilerImpl(DMNCompilerConfiguration dmnCompilerConfig) {
        this.dmnCompilerConfig = dmnCompilerConfig;
        DMNCompilerConfigurationImpl cc = (DMNCompilerConfigurationImpl) dmnCompilerConfig;
        addDRGElementCompilers(cc.getDRGElementCompilers());
        this.feel = new DMNFEELHelper(cc.getRootClassLoader(), cc.getFeelProfiles());
        this.evaluatorCompiler = new DMNEvaluatorCompiler( this, feel );
    }
    
    private void addDRGElementCompiler(DRGElementCompiler compiler) {
        drgCompilers.push(compiler);
    }

    private void addDRGElementCompilers(List<DRGElementCompiler> compilers) {
        ListIterator<DRGElementCompiler> listIterator = compilers.listIterator( compilers.size() );
        while ( listIterator.hasPrevious() ) {
            addDRGElementCompiler( listIterator.previous() );
        }
    }

    @Override
    public DMNModel compile(Resource resource, Collection<DMNModel> dmnModels) {
        try {
            DMNModel model = compile(resource.getReader(), dmnModels);
            if (model == null) {
                return null;
            } else {
                ((DMNModelImpl) model).setResource(resource);
                return model;
            }
        } catch ( IOException e ) {
            logger.error( "Error retrieving reader for resource: " + resource.getSourcePath(), e );
        }
        return null;
    }

    @Override
    public DMNModel compile(Reader source, Collection<DMNModel> dmnModels) {
        try {
            Definitions dmndefs = getMarshaller().unmarshal(source);
            DMNModel model = compile(dmndefs, dmnModels);
            return model;
        } catch ( Exception e ) {
            logger.error( "Error compiling model from source.", e );
        }
        return null;
    }

    public DMNMarshaller getMarshaller() {
        if (dmnCompilerConfig != null && !dmnCompilerConfig.getRegisteredExtensions().isEmpty()) {
            return DMNMarshallerFactory.newMarshallerWithExtensions(getDmnCompilerConfig().getRegisteredExtensions());
        } else {
            return DMNMarshallerFactory.newDefaultMarshaller();
        }
    }

    @Override
    public DMNModel compile(Definitions dmndefs, Collection<DMNModel> dmnModels) {
        if (dmndefs == null) {
            return null;
        }
        DMNModelImpl model = new DMNModelImpl(dmndefs);
        model.setRuntimeTypeCheck(((DMNCompilerConfigurationImpl) dmnCompilerConfig).getOption(RuntimeTypeCheckOption.class).isRuntimeTypeCheck());
        DMNCompilerContext ctx = new DMNCompilerContext();

        if (!dmndefs.getImport().isEmpty()) {
            for (Import i : dmndefs.getImport()) {
                if (ImportDMNResolverUtil.whichImportType(i) == ImportType.DMN) {
                    Either<String, DMNModel> resolvedResult = ImportDMNResolverUtil.resolveImportDMN(i, dmnModels, (DMNModel m) -> new QName(m.getNamespace(), m.getName()));
                    DMNModel located = resolvedResult.cata(msg -> {
                        MsgUtil.reportMessage(logger,
                                              DMNMessage.Severity.ERROR,
                                              i,
                                              model,
                                              null,
                                              null,
                                              Msg.IMPORT_NOT_FOUND_FOR_NODE,
                                              msg,
                                              i);
                        return null;
                    }, Function.identity());
                    if (located != null) {
                        String iAlias = Optional.ofNullable(i.getAdditionalAttributes().get(Import.NAME_QNAME)).orElse(located.getName());
                        model.setImportAliasForNS(iAlias, located.getNamespace(), located.getName());
                        importFromModel(model, located, iAlias);
                    }
                } else {
                    MsgUtil.reportMessage(logger,
                                          DMNMessage.Severity.ERROR,
                                          null,
                                          model,
                                          null,
                                          null,
                                          Msg.IMPORT_TYPE_UNKNOWN,
                                          i.getImportType());
                }
            }
        }

        processItemDefinitions(ctx, feel, model, dmndefs);
        processDrgElements(ctx, feel, model, dmndefs);
        return model;
    }

    private void importFromModel(DMNModelImpl model, DMNModel m, String iAlias) {
        for (ItemDefNode idn : m.getItemDefinitions()) {
            model.getTypeRegistry().registerType(idn.getType());
        }
        for (BusinessKnowledgeModelNode bkm : m.getBusinessKnowledgeModels()) {
            model.addBusinessKnowledgeModel(bkm);
        }
        for (DecisionNode dn : m.getDecisions()) {
            model.addDecision(dn);
        }
    }

    private void processItemDefinitions(DMNCompilerContext ctx, DMNFEELHelper feel, DMNModelImpl model, Definitions dmndefs) {
        Definitions.normalize(dmndefs);
        
        List<ItemDefinition> ordered = new ItemDefinitionDependenciesSorter(model.getNamespace()).sort(dmndefs.getItemDefinition());
        
        for ( ItemDefinition id : ordered ) {
            ItemDefNodeImpl idn = new ItemDefNodeImpl( id );
            DMNType type = buildTypeDef( ctx, feel, model, idn, id, true );
            idn.setType( type );
            model.addItemDefinition( idn );
        }
    }

    private void processDrgElements(DMNCompilerContext ctx, DMNFEELHelper feel, DMNModelImpl model, Definitions dmndefs) {
        for ( DRGElement e : dmndefs.getDrgElement() ) {
            boolean foundIt = false;
            for( DRGElementCompiler dc : drgCompilers ) {
                if ( dc.accept( e ) ) {
                    foundIt = true;
                    dc.compileNode(e, this, model);
                    continue;
                }
            }  
            if ( !foundIt ) {
                MsgUtil.reportMessage( logger,
                                       DMNMessage.Severity.ERROR,
                                       e,
                                       model,
                                       null,
                                       null,
                                       Msg.UNSUPPORTED_ELEMENT,
                                       e.getClass().getSimpleName(),
                                       e.getId() );
            }
        }

        for ( BusinessKnowledgeModelNode bkm : model.getBusinessKnowledgeModels() ) {
            BusinessKnowledgeModelNodeImpl bkmi = (BusinessKnowledgeModelNodeImpl) bkm;
            bkmi.addModelImportAliases(model.getImportAliasesForNS());
            for( DRGElementCompiler dc : drgCompilers ) {
                if ( bkmi.getEvaluator() == null && dc.accept( bkm ) ) {
                    dc.compileEvaluator(bkm, this, ctx, model);
                }
            }
        }

        for ( DecisionNode d : model.getDecisions() ) {
            DecisionNodeImpl di = (DecisionNodeImpl) d;
            di.addModelImportAliases(model.getImportAliasesForNS());
            for( DRGElementCompiler dc : drgCompilers ) {
                if ( di.getEvaluator() == null && dc.accept( d ) ) {
                    dc.compileEvaluator(d, this, ctx, model);
                }
            }
        }
        detectCycles( model );
    }

    private void detectCycles( DMNModelImpl model ) {
        /*
        Boolean.TRUE = node is either safe or already reported for having a cyclic dependency
        Boolean.FALSE = node is being checked at the moment
         */
        final Map<DecisionNodeImpl, Boolean> registry = new HashMap<>();
        for ( DecisionNode decision : model.getDecisions() ) {
            final DecisionNodeImpl decisionNode = (DecisionNodeImpl) decision;
            detectCycles( decisionNode, registry, model );
        }
    }

    private void detectCycles( DecisionNodeImpl node, Map<DecisionNodeImpl, Boolean> registry, DMNModelImpl model ) {
        if ( Boolean.TRUE.equals(registry.get( node ) ) ) return;
        if ( Boolean.FALSE.equals( registry.put( node, Boolean.FALSE ) ) ) {
            MsgUtil.reportMessage( logger,
                                   DMNMessage.Severity.ERROR,
                                   node.getSource(),
                                   model,
                                   null,
                                   null,
                                   Msg.CYCLIC_DEP_FOR_NODE,
                                   node.getName() );
            registry.put( node, Boolean.TRUE );
        }
        for ( DMNNode dependency : node.getDependencies().values() ) {
            if ( dependency instanceof DecisionNodeImpl ) {
                detectCycles( (DecisionNodeImpl) dependency, registry, model );
            }
        }
        registry.put( node, Boolean.TRUE );
    }


    public void linkRequirements(DMNModelImpl model, DMNBaseNode node) {
        for ( InformationRequirement ir : node.getInformationRequirement() ) {
            if ( ir.getRequiredInput() != null ) {
                String id = getId( ir.getRequiredInput() );
                InputDataNode input = model.getInputById( id );
                if ( input != null ) {
                    node.addDependency( input.getName(), input );
                } else {
                    MsgUtil.reportMessage( logger,
                                           DMNMessage.Severity.ERROR,
                                           ir.getRequiredInput(),
                                           model,
                                           null,
                                           null,
                                           Msg.REQ_INPUT_NOT_FOUND_FOR_NODE,
                                           id,
                                           node.getName() );
                }
            } else if ( ir.getRequiredDecision() != null ) {
                String id = getId( ir.getRequiredDecision() );
                DecisionNode dn = model.getDecisionById( id );
                if ( dn != null ) {
                    node.addDependency( dn.getName(), dn );
                } else {
                    MsgUtil.reportMessage( logger,
                                           DMNMessage.Severity.ERROR,
                                           ir.getRequiredDecision(),
                                           model,
                                           null,
                                           null,
                                           Msg.REQ_DECISION_NOT_FOUND_FOR_NODE,
                                           id,
                                           node.getName() );
                }
            }
        }
        for ( KnowledgeRequirement kr : node.getKnowledgeRequirement() ) {
            if ( kr.getRequiredKnowledge() != null ) {
                String id = getId( kr.getRequiredKnowledge() );
                BusinessKnowledgeModelNode bkmn = model.getBusinessKnowledgeModelById( id );
                if ( bkmn != null ) {
                    node.addDependency( bkmn.getName(), bkmn );
                } else {
                    MsgUtil.reportMessage( logger,
                                           DMNMessage.Severity.ERROR,
                                           kr.getRequiredKnowledge(),
                                           model,
                                           null,
                                           null,
                                           Msg.REQ_BKM_NOT_FOUND_FOR_NODE,
                                           id,
                                           node.getName() );
                }
            }
        }
    }

    /**
     * For the purpose of Compilation, in the DMNModel the DRGElements are stored with their full ID, so an ElementReference might reference in two forms:
     *  - #id (a local to the model ID)
     *  - namespace#id (an imported DRGElement ID)
     * This method now returns in the first case the proper ID, while leave unchanged in the latter case, in order for the ID to be reconciliable on the DMNModel. 
     */
    private String getId(DMNElementReference er) {
        String href = er.getHref();
        return href.startsWith("#") ? href.substring(1) : href;
    }

    private DMNType buildTypeDef(DMNCompilerContext ctx, DMNFEELHelper feel, DMNModelImpl dmnModel, DMNNode node, ItemDefinition itemDef, boolean topLevel) {
        BaseDMNTypeImpl type = null;
        if ( itemDef.getTypeRef() != null ) {
            // this is a reference to an existing type, so resolve the reference
            type = (BaseDMNTypeImpl) resolveTypeRef( dmnModel, node, itemDef, itemDef, itemDef.getTypeRef() );
            if ( type != null ) {
                UnaryTests allowedValuesStr = itemDef.getAllowedValues();

                // we only want to clone the type definition if it is a top level type (not a field in a composite type)
                // or if it changes the metadata for the base type
                if( topLevel || allowedValuesStr != null || itemDef.isIsCollection() != type.isCollection() ) {

                    // we have to clone this type definition into a new one
                    BaseDMNTypeImpl baseType = type;
                    type = type.clone();

                    type.setBaseType( baseType );
                    type.setNamespace( dmnModel.getNamespace() );
                    type.setName( itemDef.getName() );

                    Type baseFEELType = type.getFeelType();
                    if (baseFEELType instanceof BuiltInType) { // Then it is an ItemDefinition in place for "aliasing" a base FEEL type, for having type(itemDefname) I need to define its SimpleType.
                        type.setFeelType(new AliasFEELType(itemDef.getName(), (BuiltInType) baseFEELType));
                    }

                    type.setAllowedValues(null);
                    if ( allowedValuesStr != null ) {
                        List<UnaryTest> av = feel.evaluateUnaryTests(
                                ctx,
                                allowedValuesStr.getText(),
                                dmnModel,
                                itemDef,
                                Msg.ERR_COMPILING_ALLOWED_VALUES_LIST_ON_ITEM_DEF,
                                allowedValuesStr.getText(),
                                node.getName()
                        );
                        type.setAllowedValues( av );
                    }
                    if ( itemDef.isIsCollection() ) {
                        type.setCollection( itemDef.isIsCollection() );
                    }
                }
                if( topLevel ) {
                    DMNType registered = dmnModel.getTypeRegistry().registerType( type );
                    if( registered != type ) {
                        MsgUtil.reportMessage( logger,
                                               DMNMessage.Severity.ERROR,
                                               itemDef,
                                               dmnModel,
                                               null,
                                               null,
                                               Msg.DUPLICATED_ITEM_DEFINITION,
                                               itemDef.getName() );
                    }
                }
            }
        } else {
            // this is a composite type
            DMNCompilerHelper.checkVariableName( dmnModel, itemDef, itemDef.getName() );
            CompositeTypeImpl compType = new CompositeTypeImpl( dmnModel.getNamespace(), itemDef.getName(), itemDef.getId(), itemDef.isIsCollection() );
            for ( ItemDefinition fieldDef : itemDef.getItemComponent() ) {
                DMNCompilerHelper.checkVariableName( dmnModel, fieldDef, fieldDef.getName() );
                DMNType fieldType = buildTypeDef( ctx, feel, dmnModel, node, fieldDef, false );
                fieldType = fieldType != null ? fieldType : DMNTypeRegistry.UNKNOWN;
                compType.addField( fieldDef.getName(), fieldType );
            }
            type = compType;
            if( topLevel ) {
                DMNType registered = dmnModel.getTypeRegistry().registerType( type );
                if( registered != type ) {
                    MsgUtil.reportMessage( logger,
                                           DMNMessage.Severity.ERROR,
                                           itemDef,
                                           dmnModel,
                                           null,
                                           null,
                                           Msg.DUPLICATED_ITEM_DEFINITION,
                                           itemDef.getName() );
                }
            }
        }
        return type;
    }

    public DMNType resolveTypeRef(DMNModelImpl dmnModel, DMNNode node, NamedElement model, DMNModelInstrumentedBase localElement, QName typeRef) {
        if ( typeRef != null ) {
            QName nsAndName = getNamespaceAndName(localElement, dmnModel.getImportAliasesForNS(), typeRef);

            DMNType type = dmnModel.getTypeRegistry().resolveType(nsAndName.getNamespaceURI(), nsAndName.getLocalPart());
            if (type == null && DMNModelInstrumentedBase.URI_FEEL.equals(nsAndName.getNamespaceURI())) {
                if ( model instanceof Decision && ((Decision) model).getExpression() instanceof DecisionTable ) {
                    DecisionTable dt = (DecisionTable) ((Decision) model).getExpression();
                    if ( dt.getOutput().size() > 1 ) {
                        // implicitly define a type for the decision table result
                        CompositeTypeImpl compType = new CompositeTypeImpl( dmnModel.getNamespace(), model.getName()+"_Type", model.getId(), dt.getHitPolicy().isMultiHit() );
                        for ( OutputClause oc : dt.getOutput() ) {
                            DMNType fieldType = resolveTypeRef( dmnModel, node, model, oc, oc.getTypeRef() );
                            compType.addField( oc.getName(), fieldType );
                        }
                        dmnModel.getTypeRegistry().registerType( compType );
                        return compType;
                    } else if ( dt.getOutput().size() == 1 ) {
                        return resolveTypeRef( dmnModel, node, model, dt.getOutput().get( 0 ), dt.getOutput().get( 0 ).getTypeRef() );
                    }
                }
            } else if( type == null ) {
                MsgUtil.reportMessage( logger,
                                       DMNMessage.Severity.ERROR,
                                       localElement,
                                       dmnModel,
                                       null,
                                       null,
                                       Msg.UNKNOWN_TYPE_REF_ON_NODE,
                                       typeRef.toString(),
                                       localElement.getParentDRDElement().getIdentifierString() );
            }
            return type;
        }
        return dmnModel.getTypeRegistry().resolveType( DMNModelInstrumentedBase.URI_FEEL, BuiltInType.UNKNOWN.getName() );
    }

    /**
     * Given a typeRef in the form of prefix:localname or importalias.localname, resolves namespace and localname appropriately.
     * <br>Example: <code>feel:string</code> would be resolved as <code>http://www.omg.org/spec/FEEL/20140401, string</code>.
     * <br>Example: <code>myimport.tPerson</code> assuming an external model namespace as "http://drools.org" would be resolved as <code>http://drools.org, tPerson</code>.
     * @param localElement the local element is used to determine the namespace from the prefix if present, as in the form prefix:localname
     * @param importAliases the map of import aliases is used to determine the namespace, as in the form importalias.localname
     * @param typeRef the typeRef to be resolved.
     * @return
     */
    private QName getNamespaceAndName(DMNModelInstrumentedBase localElement, Map<String, QName> importAliases, QName typeRef) {
        if (!typeRef.getPrefix().equals(XMLConstants.DEFAULT_NS_PREFIX)) {
            return new QName(localElement.getNamespaceURI(typeRef.getPrefix()), typeRef.getLocalPart());
        } else {
            for (Entry<String, QName> alias : importAliases.entrySet()) {
                String prefix = alias.getKey() + ".";
                if (typeRef.getLocalPart().startsWith(prefix)) {
                    return new QName(alias.getValue().getNamespaceURI(), typeRef.getLocalPart().replace(prefix, ""));
                }
            }
            return new QName(localElement.getNamespaceURI(typeRef.getPrefix()), typeRef.getLocalPart());
        }
    }

    public DMNCompilerConfiguration getDmnCompilerConfig() {
        return this.dmnCompilerConfig;
    }

    public List<DMNExtensionRegister> getRegisteredExtensions() {
        if ( this.dmnCompilerConfig == null ) {
            return Collections.emptyList();
        } else {
            return this.dmnCompilerConfig.getRegisteredExtensions();
        }
    }
    
    public DMNEvaluatorCompiler getEvaluatorCompiler() {
        return evaluatorCompiler;
    }
    
}
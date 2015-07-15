/*
 * Copyright 2015 Time Warner Cable, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twcable.grabbit.server.batch.steps.jcrnodes

import com.day.cq.replication.DefaultAggregateHandler
import com.google.protobuf.ByteString
import com.twcable.grabbit.DateUtil
import com.twcable.grabbit.proto.NodeProtos
import com.twcable.grabbit.proto.NodeProtos.Node
import com.twcable.grabbit.proto.NodeProtos.Properties
import com.twcable.grabbit.proto.NodeProtos.Property
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.batch.item.ItemProcessor

import javax.jcr.Node as JcrNode
import javax.jcr.NodeIterator
import javax.jcr.Property as JcrProperty
import javax.jcr.PropertyType
import javax.jcr.Value

import static javax.jcr.PropertyType.BINARY
import static javax.jcr.PropertyType.BOOLEAN
import static javax.jcr.PropertyType.DATE
import static javax.jcr.PropertyType.DECIMAL
import static javax.jcr.PropertyType.DOUBLE
import static javax.jcr.PropertyType.LONG
import static javax.jcr.PropertyType.NAME
import static javax.jcr.PropertyType.PATH
import static javax.jcr.PropertyType.REFERENCE
import static javax.jcr.PropertyType.STRING
import static javax.jcr.PropertyType.WEAKREFERENCE
import static org.apache.jackrabbit.JcrConstants.JCR_CREATED
import static org.apache.jackrabbit.JcrConstants.JCR_LASTMODIFIED
import static org.apache.jackrabbit.JcrConstants.JCR_MIXINTYPES
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE

/**
 * A Custom ItemProcessor that effectively acts as a Marshaller for a Jcr Node.
 */
@Slf4j
@CompileStatic
class JcrNodesProcessor implements ItemProcessor<JcrNode, Node> {

    private String contentAfterDate


    void setContentAfterDate(String contentAfterDate) {
        this.contentAfterDate = contentAfterDate
    }

    /**
     * Converts a JCR Node to Protocol Buffer Message {@link NodeProtos.Node} object.
     * Returns a null if current Node's processing needs to be ignored like for "rep:policy" nodes
     */
    @Override
    Node process(JcrNode jcrNode) throws Exception {

        //TODO: Access Control Lists nodes are not supported right now.
        if (!jcrNode || jcrNode.path.contains("rep:policy")) {
            log.info "Ignoring current node ${jcrNode}"
            return null
        }

        if (contentAfterDate) {
            final Date afterDate = DateUtil.getDateFromISOString(contentAfterDate)
            log.debug "ContentAfterDate received : ${afterDate}. Will ignore content created or modified before the afterDate"
            final date = getDate(jcrNode)
            if (!date) {
                //we want delta content but node doesn't have any date property to compare with. So we ignore it
                return null
            }
            if (date.before(afterDate)) {
                log.debug "Not sending any data older than ${afterDate}"
                return null
            }
        }

        if (isRequiredChildNode(jcrNode))
            return null

        return buildNode(jcrNode)
    }

    private static Node buildNode(JcrNode jcrNode) {
        final List<JcrProperty> properties = jcrNode.properties.toList()
        Node.Builder nodeBuilder = Node.newBuilder()
        nodeBuilder.setName(jcrNode.path)

        if (isHierarchyNode(jcrNode) && !isRequiredChildNode(jcrNode)) {
            List<JcrNode> childNodes = new ArrayList<JcrNode>()
            getRequiredChildNodes(childNodes, jcrNode).each {
                nodeBuilder.addChildNode(buildNode(it))
            }
        }

        Properties.Builder propertiesBuilder = Properties.newBuilder()
        properties.each { JcrProperty jcrProperty ->
            //Before adding a property to the Current Node Proto message, check if the property
            //is Valid and if it should be actually sent to the client
            if (isPropertyTransferable(jcrProperty)) {
                Property property = toProperty(jcrProperty)
                propertiesBuilder.addProperty(property)
            }
        }

        nodeBuilder.setProperties(propertiesBuilder.build())
        nodeBuilder.build()
    }

    private static List<JcrNode> getRequiredChildNodes(List<JcrNode> childNodes, JcrNode jcrNode) {
        NodeIterator nodeIterator = jcrNode.getNodes()
        while (nodeIterator.hasNext()) {
            JcrNode currentNode = nodeIterator.nextNode()

            if (isRequiredChildNode(currentNode)) {
                childNodes.add(currentNode)
                childNodes = getRequiredChildNodes(childNodes, currentNode)
            }
        }
        return childNodes
    }

    private static boolean isRequiredChildNode(JcrNode node) {
        return node.getDefinition().isMandatory() || node.getParent().getDefinition().requiredPrimaryTypes.contains(node.getPrimaryNodeType())
    }

    private static boolean isHierarchyNode(JcrNode node) {
        return new DefaultAggregateHandler().isAggregateRoot(node)
    }

    /**
     * Returns the "jcr:created", "jcr:lastModified" or "cq:lastModified" date property
     * for current Jcr Node
     * Returns null of none of the 3 are found
     * @return
     */
    private static Date getDate(JcrNode jcrNode) {
        final String CQ_LAST_MODIFIED = "cq:lastModified"
        if (jcrNode.hasProperty(JCR_CREATED)) {
            return jcrNode.getProperty(JCR_CREATED).date.time
        }
        else if (jcrNode.hasProperty(JCR_LASTMODIFIED)) {
            return jcrNode.getProperty(JCR_LASTMODIFIED).date.time
        }
        else if (jcrNode.hasProperty(CQ_LAST_MODIFIED)) {
            return jcrNode.getProperty(CQ_LAST_MODIFIED).date.time
        }
        return null
    }

    /**
     * Checks if current Jcr Property is valid to be written out to the client or not
     * @param jcrProperty
     * @return
     */
    private static boolean isPropertyTransferable(JcrProperty jcrProperty) {
        //If property is "jcr:lastModified", we don't want to send this property to the client. If we send it, and
        //the client writes it to JCR, then we can have lastModified date for a node that is older than the creation
        //date itself
        if (jcrProperty.name == JCR_LASTMODIFIED) {
            return false
        }

        if ([JCR_PRIMARYTYPE, JCR_MIXINTYPES].contains(jcrProperty.name)) {
            return true
        }

        !jcrProperty.definition.isProtected()
    }

    /**
     * Accepts a Jcr Property and marshals it to a NodeProtos.Property message object
     * @param jcrProperty
     * @return
     */
    //TODO : This method needs a refactor
    private static Property toProperty(JcrProperty jcrProperty) {
        Property.Builder propertyBuilder = Property.newBuilder()
        propertyBuilder.setName(jcrProperty.name)

        final int type = jcrProperty.type

        switch (type) {
            case STRING:
                if (!jcrProperty.multiple) {
                    Value value = jcrProperty.value
                    propertyBuilder.setValue(NodeProtos.Value.newBuilder().setStringValue(value.string))
                }
                else {
                    Value[] values = jcrProperty.values
                    Collection<NodeProtos.Value> protoValues = values.collect { Value value ->
                        NodeProtos.Value.newBuilder().setStringValue(value.string).build()
                    }
                    propertyBuilder.setValues(
                        NodeProtos.Values.newBuilder().addAllValue(protoValues).build()
                    )
                }
                break
            case BINARY:
                //no multiple values
                if (!jcrProperty.multiple) {
                    Value value = jcrProperty.value
                    propertyBuilder.setValue(NodeProtos.Value.newBuilder().setBytesValue(ByteString.copyFrom(value.binary.stream.bytes)))
                }
                break
            case BOOLEAN:
                if (!jcrProperty.multiple) {
                    Value value = jcrProperty.value
                    propertyBuilder.setValue(NodeProtos.Value.newBuilder().setStringValue(value.boolean.toString()))
                }
                else {
                    Value[] values = jcrProperty.values
                    Collection<NodeProtos.Value> protoValues = values.collect { Value value ->
                        NodeProtos.Value.newBuilder().setStringValue(value.boolean.toString()).build()
                    }
                    propertyBuilder.setValues(
                        NodeProtos.Values.newBuilder().addAllValue(protoValues).build()
                    )
                }
                break
            case DATE:
                if (!jcrProperty.multiple) {
                    Value value = jcrProperty.value
                    propertyBuilder.setValue(NodeProtos.Value.newBuilder().setStringValue(DateUtil.getISOStringFromCalendar(value.date)))
                }
                else {
                    Value[] values = jcrProperty.values
                    Collection<NodeProtos.Value> protoValues = values.collect { Value value ->
                        NodeProtos.Value.newBuilder().setStringValue(DateUtil.getISOStringFromCalendar(value.date)).build()
                    }
                    propertyBuilder.setValues(
                        NodeProtos.Values.newBuilder().addAllValue(protoValues).build()
                    )
                }
                break
            case DECIMAL:
                if (!jcrProperty.multiple) {
                    Value value = jcrProperty.value
                    propertyBuilder.setValue(NodeProtos.Value.newBuilder().setStringValue(value.decimal.toString()))
                }
                else {
                    Value[] values = jcrProperty.values
                    Collection<NodeProtos.Value> protoValues = values.collect { Value value ->
                        NodeProtos.Value.newBuilder().setStringValue(value.decimal.toString()).build()
                    }
                    propertyBuilder.setValues(
                        NodeProtos.Values.newBuilder().addAllValue(protoValues).build()
                    )
                }
                break
            case DOUBLE:
                if (!jcrProperty.multiple) {
                    Value value = jcrProperty.value
                    propertyBuilder.setValue(NodeProtos.Value.newBuilder().setStringValue(value.double.toString()))
                }
                else {
                    Value[] values = jcrProperty.values
                    Collection<NodeProtos.Value> protoValues = values.collect { Value value ->
                        NodeProtos.Value.newBuilder().setStringValue(value.double.toString()).build()
                    }
                    propertyBuilder.setValues(
                        NodeProtos.Values.newBuilder().addAllValue(protoValues).build()
                    )
                }
                break
            case LONG:
                if (!jcrProperty.multiple) {
                    Value value = jcrProperty.value
                    propertyBuilder.setValue(NodeProtos.Value.newBuilder().setStringValue(value.long.toString()))
                }
                else {
                    Value[] values = jcrProperty.values
                    Collection<NodeProtos.Value> protoValues = values.collect { Value value ->
                        NodeProtos.Value.newBuilder().setStringValue(value.long.toString()).build()
                    }
                    propertyBuilder.setValues(
                        NodeProtos.Values.newBuilder().addAllValue(protoValues).build()
                    )
                }
                break
            case NAME:
                if (!jcrProperty.multiple) {
                    Value value = jcrProperty.value
                    propertyBuilder.setValue(NodeProtos.Value.newBuilder().setStringValue(value.string))
                }
                else {
                    Value[] values = jcrProperty.values
                    Collection<NodeProtos.Value> protoValues = values.collect { Value value ->
                        NodeProtos.Value.newBuilder().setStringValue(value.string).build()
                    }
                    propertyBuilder.setValues(
                        NodeProtos.Values.newBuilder().addAllValue(protoValues).build()
                    )
                }
                break
            case PATH:
                if (!jcrProperty.multiple) {
                    Value value = jcrProperty.value
                    propertyBuilder.setValue(NodeProtos.Value.newBuilder().setStringValue(value.string))
                }
                else {
                    Value[] values = jcrProperty.values
                    Collection<NodeProtos.Value> protoValues = values.collect { Value value ->
                        NodeProtos.Value.newBuilder().setStringValue(value.string).build()
                    }
                    propertyBuilder.setValues(
                        NodeProtos.Values.newBuilder().addAllValue(protoValues).build()
                    )
                }
                break
            case PropertyType.URI:
                if (!jcrProperty.multiple) {
                    Value value = jcrProperty.value
                    propertyBuilder.setValue(NodeProtos.Value.newBuilder().setStringValue(value.string))
                }
                else {
                    Value[] values = jcrProperty.values
                    Collection<NodeProtos.Value> protoValues = values.collect { Value value ->
                        NodeProtos.Value.newBuilder().setStringValue(value.string).build()
                    }
                    propertyBuilder.setValues(
                        NodeProtos.Values.newBuilder().addAllValue(protoValues).build()
                    )
                }
                break
        //TODO: Is it correct to ignore this? (as Reference value would mean different for Server and for Client
            case REFERENCE:
                break
        //TODO: Is it correct to ignore this? (seems similar to REFERENCE to me)
            case WEAKREFERENCE:
                break
        }

        propertyBuilder.setType(jcrProperty.type)
        propertyBuilder.build()
    }
}

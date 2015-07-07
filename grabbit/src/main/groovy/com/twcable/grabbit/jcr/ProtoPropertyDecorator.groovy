package com.twcable.grabbit.jcr

import com.twcable.grabbit.DateUtil
import com.twcable.grabbit.proto.NodeProtos.Property as ProtoProperty
import com.twcable.grabbit.proto.NodeProtos.Value as ProtoValue
import groovy.transform.CompileStatic
import org.apache.jackrabbit.value.ValueFactoryImpl

import javax.annotation.Nonnull
import javax.jcr.Node as JCRNode
import javax.jcr.Property
import javax.jcr.PropertyType
import javax.jcr.Value
import javax.jcr.ValueFormatException

import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE

@CompileStatic
class ProtoPropertyDecorator {

    @Delegate
    ProtoProperty innerProtoProperty

    ProtoPropertyDecorator(@Nonnull ProtoProperty protoProperty) {
        this.innerProtoProperty = protoProperty
    }

    void writeToNode(@Nonnull JCRNode node) {
        try {
            if (!innerProtoProperty.hasValues()) {
                node.setProperty(innerProtoProperty.name, getPropertyValue(), innerProtoProperty.type)
            }
            else {
                Value[] values = getPropertyValues()
                node.setProperty(innerProtoProperty.name, values, innerProtoProperty.type)
            }
        }
        catch (ValueFormatException ex) {
            if (ex.message.contains("Multivalued property can not be set to a single value")) {
                //If this is the exception, that means that a property with the name already exists
                final Property currentProperty = node.getProperty(innerProtoProperty.name)
                if (currentProperty.multiple) {
                    final Value[] values = [getPropertyValue()]
                    node.setProperty(innerProtoProperty.name, values, innerProtoProperty.type)
                }
            }
            else if (ex.message.contains("Single-valued property can not be set to an array of values")) {
                node.setProperty(innerProtoProperty.name, getPropertyValues().first(), innerProtoProperty.type)
            }
        }
    }

    boolean isPrimary() {
        innerProtoProperty.name == JCR_PRIMARYTYPE
    }

    private Value getPropertyValue() throws ValueFormatException {
        getJCRValueFromProtoValue(innerProtoProperty.getValue())
    }

    private Value[] getPropertyValues() throws ValueFormatException {
        return innerProtoProperty.values.valueList.collect { ProtoValue protoValue -> getJCRValueFromProtoValue(protoValue) } as Value[]
    }

    private Value getJCRValueFromProtoValue(ProtoValue value) throws ValueFormatException {

        final valueFactory = ValueFactoryImpl.getInstance()

        if (innerProtoProperty.type == PropertyType.BINARY) {
            final binary = valueFactory.createBinary(new ByteArrayInputStream(value.bytesValue.toByteArray()))
            return valueFactory.createValue(binary)
        }
        else if (innerProtoProperty.type == PropertyType.DATE) {
            final date = DateUtil.getCalendarFromISOString(value.stringValue)
            return valueFactory.createValue(date)
        }

        return valueFactory.createValue(value.stringValue, innerProtoProperty.type)
    }
}

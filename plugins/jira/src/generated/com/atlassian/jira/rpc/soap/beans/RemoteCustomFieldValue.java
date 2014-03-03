/**
 * RemoteCustomFieldValue.java
 *
 * This file was auto-generated from WSDL
 * by the Apache Axis 1.4 Apr 22, 2006 (06:55:48 PDT) WSDL2Java emitter.
 */

package com.atlassian.jira.rpc.soap.beans;

public class RemoteCustomFieldValue implements java.io.Serializable {
    private java.lang.String customfieldId;

    private java.lang.String key;

    private java.lang.String[] values;

    public RemoteCustomFieldValue() {
    }

    public RemoteCustomFieldValue(java.lang.String customfieldId, java.lang.String key, java.lang.String[] values) {
        this.customfieldId = customfieldId;
        this.key = key;
        this.values = values;
    }

    /**
     * Gets the customfieldId value for this RemoteCustomFieldValue.
     * 
     * @return customfieldId
     */
    public java.lang.String getCustomfieldId() {
        return customfieldId;
    }

    /**
     * Sets the customfieldId value for this RemoteCustomFieldValue.
     * 
     * @param customfieldId
     */
    public void setCustomfieldId(java.lang.String customfieldId) {
        this.customfieldId = customfieldId;
    }

    /**
     * Gets the key value for this RemoteCustomFieldValue.
     * 
     * @return key
     */
    public java.lang.String getKey() {
        return key;
    }

    /**
     * Sets the key value for this RemoteCustomFieldValue.
     * 
     * @param key
     */
    public void setKey(java.lang.String key) {
        this.key = key;
    }

    /**
     * Gets the values value for this RemoteCustomFieldValue.
     * 
     * @return values
     */
    public java.lang.String[] getValues() {
        return values;
    }

    /**
     * Sets the values value for this RemoteCustomFieldValue.
     * 
     * @param values
     */
    public void setValues(java.lang.String[] values) {
        this.values = values;
    }

    private java.lang.Object __equalsCalc = null;

    public synchronized boolean equals(java.lang.Object obj) {
        if (!(obj instanceof RemoteCustomFieldValue))
            return false;
        RemoteCustomFieldValue other = (RemoteCustomFieldValue) obj;
        if (obj == null)
            return false;
        if (this == obj)
            return true;
        if (__equalsCalc != null) {
            return (__equalsCalc == obj);
        }
        __equalsCalc = obj;
        boolean _equals;
        _equals = true
                && ((this.customfieldId == null && other.getCustomfieldId() == null) || (this.customfieldId != null && this.customfieldId
                        .equals(other.getCustomfieldId())))
                && ((this.key == null && other.getKey() == null) || (this.key != null && this.key.equals(other.getKey())))
                && ((this.values == null && other.getValues() == null) || (this.values != null && java.util.Arrays.equals(
                        this.values, other.getValues())));
        __equalsCalc = null;
        return _equals;
    }

    private boolean __hashCodeCalc = false;

    public synchronized int hashCode() {
        if (__hashCodeCalc) {
            return 0;
        }
        __hashCodeCalc = true;
        int _hashCode = 1;
        if (getCustomfieldId() != null) {
            _hashCode += getCustomfieldId().hashCode();
        }
        if (getKey() != null) {
            _hashCode += getKey().hashCode();
        }
        if (getValues() != null) {
            for (int i = 0; i < java.lang.reflect.Array.getLength(getValues()); i++) {
                java.lang.Object obj = java.lang.reflect.Array.get(getValues(), i);
                if (obj != null && !obj.getClass().isArray()) {
                    _hashCode += obj.hashCode();
                }
            }
        }
        __hashCodeCalc = false;
        return _hashCode;
    }

    // Type metadata
    private static org.apache.axis.description.TypeDesc typeDesc = new org.apache.axis.description.TypeDesc(
            RemoteCustomFieldValue.class, true);

    static {
        typeDesc.setXmlType(new javax.xml.namespace.QName("http://beans.soap.rpc.jira.atlassian.com", "RemoteCustomFieldValue"));
        org.apache.axis.description.ElementDesc elemField = new org.apache.axis.description.ElementDesc();
        elemField.setFieldName("customfieldId");
        elemField.setXmlName(new javax.xml.namespace.QName("", "customfieldId"));
        elemField.setXmlType(new javax.xml.namespace.QName("http://www.w3.org/2001/XMLSchema", "string"));
        elemField.setNillable(true);
        typeDesc.addFieldDesc(elemField);
        elemField = new org.apache.axis.description.ElementDesc();
        elemField.setFieldName("key");
        elemField.setXmlName(new javax.xml.namespace.QName("", "key"));
        elemField.setXmlType(new javax.xml.namespace.QName("http://www.w3.org/2001/XMLSchema", "string"));
        elemField.setNillable(true);
        typeDesc.addFieldDesc(elemField);
        elemField = new org.apache.axis.description.ElementDesc();
        elemField.setFieldName("values");
        elemField.setXmlName(new javax.xml.namespace.QName("", "values"));
        elemField.setXmlType(new javax.xml.namespace.QName("http://www.w3.org/2001/XMLSchema", "string"));
        elemField.setNillable(true);
        typeDesc.addFieldDesc(elemField);
    }

    /**
     * Return type metadata object
     */
    public static org.apache.axis.description.TypeDesc getTypeDesc() {
        return typeDesc;
    }

    /**
     * Get Custom Serializer
     */
    public static org.apache.axis.encoding.Serializer getSerializer(java.lang.String mechType, java.lang.Class _javaType,
            javax.xml.namespace.QName _xmlType) {
        return new org.apache.axis.encoding.ser.BeanSerializer(_javaType, _xmlType, typeDesc);
    }

    /**
     * Get Custom Deserializer
     */
    public static org.apache.axis.encoding.Deserializer getDeserializer(java.lang.String mechType, java.lang.Class _javaType,
            javax.xml.namespace.QName _xmlType) {
        return new org.apache.axis.encoding.ser.BeanDeserializer(_javaType, _xmlType, typeDesc);
    }

}

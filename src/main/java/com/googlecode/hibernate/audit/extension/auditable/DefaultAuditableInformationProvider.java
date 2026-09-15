/**
 * Copyright (C) 2009 Krasimir Chobantonov <kchobantonov@yahoo.com>
 * This file is part of Hibernate Audit.

 * Hibernate Audit is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your
 * option) any later version.
 * 
 * Hibernate Audit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with Hibernate Audit.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.googlecode.hibernate.audit.extension.auditable;

import org.hibernate.Session;
import org.hibernate.boot.Metadata;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.type.EntityType;
import org.hibernate.type.Type;

public class DefaultAuditableInformationProvider implements AuditableInformationProvider {

    private AuditableInformationProvider provider;

    public DefaultAuditableInformationProvider() {

    }

    public DefaultAuditableInformationProvider(AuditableInformationProvider provider) {
        this.provider = provider;
    }

    public boolean isAuditable(String entityName) {
        if (entityName.startsWith("com.googlecode.hibernate.audit.")) {
            return false;
        }

        if (provider != null) {
            return provider.isAuditable(entityName);
        }
        return true;
    }

    public boolean isAuditable(String entityName, String propertyName) {
        if (!isAuditable(entityName)) {
            return false;
        }

        if (provider != null) {
            return provider.isAuditable(entityName, propertyName);
        }
        return true;
    }

    public String getAuditTypeClassName(Metadata metadata, String entityName) {
        if (provider != null) {
            return provider.getAuditTypeClassName(metadata, entityName);
        }
        return auditTypeClassName(metadata.getEntityBinding(entityName));
    }
    
    
    public String getEntityName(Metadata metadata, Session session, String auditTypeClassName) {
        if (provider != null) {
            return provider.getEntityName(metadata, session, auditTypeClassName);
        }
        for (PersistentClass classMapping : metadata.getEntityBindings()) {
            if (auditTypeClassName(classMapping).equals(auditTypeClassName)) {
                return classMapping.getEntityName();
            }
        }
        return auditTypeClassName;
    }

    /**
     * The name an entity is audited under: its mapped class, else its proxy interface, else the entity name
     * itself for entity-name-only (dynamic-map) mappings, which have no class at all.
     */
    private static String auditTypeClassName(PersistentClass classMapping) {
        Class<?> mappedClass = classMapping.getMappedClass();
        if (mappedClass == null) {
            mappedClass = classMapping.getProxyInterface();
        }
        return mappedClass != null ? mappedClass.getName() : classMapping.getEntityName();
    }
    
    public String getAuditTypeClassName(Metadata metadata, Type type) {
        if (provider != null) {
            return provider.getAuditTypeClassName(metadata, type);
        }
    	if (type.isEntityType()) {
    		return getAuditTypeClassName(metadata, ((EntityType) type).getName());
    	}
		return type.getReturnedClass().getName();
    }
}

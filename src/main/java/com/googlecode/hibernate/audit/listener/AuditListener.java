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
package com.googlecode.hibernate.audit.listener;

import java.io.Serializable;

import org.hibernate.HibernateException;
import org.hibernate.boot.Metadata;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.config.spi.StandardConverters;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.PostCollectionRecreateEvent;
import org.hibernate.event.spi.PostCollectionRecreateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.event.spi.PreCollectionRemoveEvent;
import org.hibernate.event.spi.PreCollectionRemoveEventListener;
import org.hibernate.event.spi.PreCollectionUpdateEvent;
import org.hibernate.event.spi.PreCollectionUpdateEventListener;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.hibernate.audit.HibernateAudit;
import com.googlecode.hibernate.audit.Version;
import com.googlecode.hibernate.audit.configuration.AuditConfiguration;
import com.googlecode.hibernate.audit.configuration.AuditConfigurationObserver;
import com.googlecode.hibernate.audit.configuration.ConfigurationHolder;
import com.googlecode.hibernate.audit.synchronization.AuditProcess;
import com.googlecode.hibernate.audit.synchronization.work.AuditWorkUnit;
import com.googlecode.hibernate.audit.synchronization.work.DeleteAuditWorkUnit;
import com.googlecode.hibernate.audit.synchronization.work.InsertAuditWorkUnit;
import com.googlecode.hibernate.audit.synchronization.work.InsertCollectionAuditWorkUnit;
import com.googlecode.hibernate.audit.synchronization.work.RemoveCollectionAuditWorkUnit;
import com.googlecode.hibernate.audit.synchronization.work.UpdateAuditWorkUnit;
import com.googlecode.hibernate.audit.synchronization.work.UpdateCollectionAuditWorkUnit;

public class AuditListener implements PostInsertEventListener, PostUpdateEventListener,
        PostDeleteEventListener, PreCollectionUpdateEventListener,
        PreCollectionRemoveEventListener,PostCollectionRecreateEventListener {
    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private static final Logger log = LoggerFactory.getLogger(AuditListener.class);
    private AuditConfiguration auditConfiguration;
    private boolean recordEmptyCollectionsOnInsert = true;
    
    public void cleanup() {
        if (auditConfiguration != null && auditConfiguration.getSessionFactory() != null) {
            ConfigurationHolder.removeAuditConfiguration(auditConfiguration.getSessionFactory());
        }
    }

    public void initialize(SessionFactoryImplementor sessionFactory,
            Metadata metadata, SessionFactoryServiceRegistry serviceRegistry) {
        try {
            recordEmptyCollectionsOnInsert = serviceRegistry.getService(ConfigurationService.class)
                                                            .getSetting(HibernateAudit.AUDIT_RECORD_EMPTY_COLLECTIONS_ON_INSERT_PROPERTY,
                                                                    StandardConverters.BOOLEAN, true);

            auditConfiguration = ConfigurationHolder.getAuditConfiguration(sessionFactory);

            if (auditConfiguration == null) {
                Version.touch();
                auditConfiguration = new AuditConfiguration(sessionFactory, metadata);

                processDynamicUpdate(metadata, serviceRegistry);

                ConfigurationHolder.putAuditConfiguration(sessionFactory, auditConfiguration);

                processAuditConfigurationObserver(serviceRegistry);
            }
        } catch (RuntimeException e) {
            if (log.isErrorEnabled()) {
                log.error("RuntimeExcpetion occured during AuditListener initialization, will re-throw the exception", e);
            }
            throw e;
        }
    }

    private void processDynamicUpdate(Metadata metadata, SessionFactoryServiceRegistry serviceRegistry) {
        if (serviceRegistry.getService(ConfigurationService.class).getSetting(HibernateAudit.AUDIT_SET_DYNAMIC_UPDATE_FOR_AUDITED_MODEL_PROPERTY, StandardConverters.BOOLEAN, false)) {
            for (PersistentClass persistentClass : metadata.getEntityBindings()) {
                persistentClass.setDynamicUpdate(true);
                if (log.isInfoEnabled()) {
                    log.info("Set dynamic-update to true for entity: " + persistentClass.getEntityName());
                }
            }
        }
    }

    private void processAuditConfigurationObserver(SessionFactoryServiceRegistry serviceRegistry) {
        String observerClazzProperty = serviceRegistry.getService(ConfigurationService.class)
                                                      .getSetting(HibernateAudit.AUDIT_CONFIGURATION_OBSERVER_PROPERTY,
                                                              StandardConverters.STRING, null);

        if (observerClazzProperty != null) {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            Class observerClazz = null;
            try {
                observerClazz = contextClassLoader.loadClass(observerClazzProperty);
            } catch (ClassNotFoundException ignored) {
            }

            try {
                if (observerClazz == null) {
                    observerClazz = AuditListener.class.forName(observerClazzProperty);
                }
            } catch (ClassNotFoundException e) {
                throw new HibernateException("Unable to find audit configuration observer class:" + observerClazzProperty, e);
            }

            try {
                AuditConfigurationObserver observer = (AuditConfigurationObserver) observerClazz.newInstance();

                observer.auditConfigurationCreated(auditConfiguration);
            } catch (InstantiationException e) {
                throw new HibernateException("Unable to instantiate audit configuration observer from class:" + observerClazzProperty, e);
            } catch (IllegalAccessException e) {
                throw new HibernateException("Unable to instantiate audit configuration observer from class:" + observerClazzProperty, e);
            } catch (ClassCastException e) {
                throw new HibernateException("Audit configuration observer class:" + observerClazzProperty + " should implement " + AuditConfigurationObserver.class.getName(), e);
            }
        }
    }

    public void onPostInsert(PostInsertEvent event) {
        try {
            String entityName = event.getPersister().getEntityName();

            if (auditConfiguration.getExtensionManager().getAuditableInformationProvider().isAuditable(entityName)) {
                AuditProcess auditProcess = auditConfiguration.getAuditProcessManager().get(event.getSession());

                AuditWorkUnit workUnit = new InsertAuditWorkUnit(entityName, (Serializable) event.getId(),
                        event.getEntity(), event.getPersister());
                auditProcess.addWorkUnit(workUnit);
            }
        } catch (RuntimeException e) {
            if (log.isErrorEnabled()) {
                log.error("RuntimeException occured during onPostInsert, will re-throw the exception", e);
            }
            throw e;
        }
    }

    public void onPostUpdate(PostUpdateEvent event) {
        try {
            String entityName = event.getPersister().getEntityName();

            if (auditConfiguration.getExtensionManager().getAuditableInformationProvider().isAuditable(entityName)) {
                AuditProcess auditProcess = auditConfiguration.getAuditProcessManager().get(event.getSession());

                AuditWorkUnit workUnit = new UpdateAuditWorkUnit(entityName, (Serializable)event.getId(),
                        event.getEntity(), event.getPersister(), event.getOldState(), event.getState());
                auditProcess.addWorkUnit(workUnit);
            }
        } catch (RuntimeException e) {
            if (log.isErrorEnabled()) {
                log.error("RuntimeException occured during onPostUpdate, will re-throw the exception", e);
            }
            throw e;
        }
    }

    public void onPostDelete(PostDeleteEvent event) {
        try {
            String entityName = event.getPersister().getEntityName();

            if (auditConfiguration.getExtensionManager().getAuditableInformationProvider().isAuditable(entityName)) {
                AuditProcess auditProcess = auditConfiguration.getAuditProcessManager().get(event.getSession());

                AuditWorkUnit workUnit = new DeleteAuditWorkUnit(entityName, (Serializable)event.getId(),
                        event.getEntity(), event.getPersister());
                auditProcess.addWorkUnit(workUnit);
            }
        } catch (RuntimeException e) {
            if (log.isErrorEnabled()) {
                log.error("RuntimeException occured during onPostDelete, will re-throw the exception", e);
            }
            throw e;
        }
    }

    public boolean requiresPostCommitHanding(EntityPersister persister) {
    	return auditConfiguration.getExtensionManager().getAuditableInformationProvider().isAuditable(persister.getEntityName());
    }
    
    public void onPostRecreateCollection(PostCollectionRecreateEvent event) {
        try {
            String entityName = event.getAffectedOwnerEntityName();

            if (auditConfiguration.getExtensionManager().getAuditableInformationProvider().isAuditable(entityName) && (recordEmptyCollectionsOnInsert || !event.getCollection().empty())) {
                AuditProcess auditProcess = auditConfiguration.getAuditProcessManager().get(event.getSession());
                AuditWorkUnit workUnit = new InsertCollectionAuditWorkUnit(entityName, (Serializable)event.getAffectedOwnerIdOrNull(),
                        event.getAffectedOwnerOrNull(), event.getCollection());
                auditProcess.addWorkUnit(workUnit);
            }
        } catch (RuntimeException e) {
            if (log.isErrorEnabled()) {
                log.error("RuntimeException occured during onPostRecreateCollection, will re-throw the exception", e);
            }
            throw e;
        }
    }

    public void onPreUpdateCollection(PreCollectionUpdateEvent event) {
        try {
            String entityName = event.getAffectedOwnerEntityName();

            if (auditConfiguration.getExtensionManager().getAuditableInformationProvider().isAuditable(entityName)) {

                AuditProcess auditProcess = auditConfiguration.getAuditProcessManager().get(event.getSession());
                AuditWorkUnit workUnit = new UpdateCollectionAuditWorkUnit(entityName, (Serializable)event.getAffectedOwnerIdOrNull(),
                        event.getAffectedOwnerOrNull(), event.getCollection());

                auditProcess.addWorkUnit(workUnit);
            }
        } catch (RuntimeException e) {
            if (log.isErrorEnabled()) {
                log.error("RuntimeException occured during onPreUpdateCollection, will re-throw the exception", e);
            }
            throw e;
        }
    }

    public void onPreRemoveCollection(PreCollectionRemoveEvent event) {
        try {
            String entityName = event.getAffectedOwnerEntityName();

            if (auditConfiguration.getExtensionManager().getAuditableInformationProvider().isAuditable(entityName)) {
                AuditProcess auditProcess = auditConfiguration.getAuditProcessManager().get(event.getSession());
                AuditWorkUnit workUnit = new RemoveCollectionAuditWorkUnit(entityName, (Serializable)event.getAffectedOwnerIdOrNull(),
                        event.getAffectedOwnerOrNull(), event.getCollection());

                auditProcess.addWorkUnit(workUnit);
            }
        } catch (RuntimeException e) {
            if (log.isErrorEnabled()) {
                log.error("RuntimeException occured during onPreRemoveCollection, will re-throw the exception", e);
            }
            throw e;
        }
    }
}
package com.googlecode.hibernate.audit;

import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.DuplicationStrategy;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.hibernate.audit.configuration.ConfigurationHolder;
import com.googlecode.hibernate.audit.listener.AuditListener;
import com.googlecode.hibernate.audit.listener.AuditSessionFactoryObserver;

public class AuditIntegrator implements Integrator {

    private static final Logger LOG = LoggerFactory.getLogger(AuditIntegrator.class);

    private static final String AUTO_REGISTER = "com.googlecode.hibernate.audit.listener.autoRegister";


    public void integrate(Metadata metadata, SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {
        final Object autoRegister = sessionFactory.getProperties().get(AUTO_REGISTER);
        if (autoRegister != null && !Boolean.parseBoolean((String) autoRegister)) {
            LOG.debug("Skipping HibernateAudit listener auto registration");
            return;
        }

        final EventListenerRegistry listenerRegistry = serviceRegistry.getService(EventListenerRegistry.class);
        listenerRegistry.addDuplicationStrategy(new DuplicationStrategy() {
            public boolean areMatch(Object listener, Object original) {
                return listener.getClass().equals(original.getClass()) && AuditListener.class.isInstance(listener);
            }

            public Action getAction() {
                return Action.KEEP_ORIGINAL;
            }
        });

        AuditListener auditListener = ConfigurationHolder.getAuditListener(sessionFactory);

        if (auditListener == null) {
            auditListener = new AuditListener();
            ConfigurationHolder.putAuditListener(sessionFactory, auditListener);
        }

        listenerRegistry.appendListeners(EventType.POST_INSERT, auditListener);
        listenerRegistry.appendListeners(EventType.POST_UPDATE, auditListener);
        listenerRegistry.appendListeners(EventType.POST_DELETE, auditListener);
        listenerRegistry.appendListeners(EventType.POST_COLLECTION_RECREATE, auditListener);
        listenerRegistry.appendListeners(EventType.PRE_COLLECTION_UPDATE, auditListener);
        listenerRegistry.appendListeners(EventType.PRE_COLLECTION_REMOVE, auditListener);

        auditListener.initialize(sessionFactory, metadata, serviceRegistry);

        sessionFactory.addObserver(new AuditSessionFactoryObserver(ConfigurationHolder.getAuditConfiguration(sessionFactory)));
    }

    public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {
        AuditListener auditListener = ConfigurationHolder.removeAuditListener(sessionFactory);
        if (auditListener != null) {
            auditListener.cleanup();
        }
    }

}

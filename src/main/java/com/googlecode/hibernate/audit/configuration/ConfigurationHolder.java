package com.googlecode.hibernate.audit.configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.engine.spi.SessionFactoryImplementor;

import com.googlecode.hibernate.audit.listener.AuditListener;

/**
 * Single place for audit configuration.
 * <p>
 * Created by EugenMaysyuk on 8/3/2017.
 */
public class ConfigurationHolder {
    private static final Map<SessionFactoryImplementor, AuditConfiguration> AUDIT_CONFIGURATION_MAP = new ConcurrentHashMap<>(16);
    private static final Map<SessionFactoryImplementor, AuditListener> AUDIT_LISTENER_MAP = new ConcurrentHashMap<>(16);

    public static AuditConfiguration putAuditConfiguration(SessionFactoryImplementor sessionFactory, AuditConfiguration auditConfiguration) {
        return AUDIT_CONFIGURATION_MAP.put(sessionFactory, auditConfiguration);
    }

    public static AuditConfiguration getAuditConfiguration(SessionFactoryImplementor sessionFactory) {
        return AUDIT_CONFIGURATION_MAP.get(sessionFactory);
    }

    public static AuditConfiguration removeAuditConfiguration(SessionFactoryImplementor sessionFactory) {
        return AUDIT_CONFIGURATION_MAP.remove(sessionFactory);
    }

    public static AuditListener getAuditListener(SessionFactoryImplementor sessionFactory) {
        return AUDIT_LISTENER_MAP.get(sessionFactory);
    }

    public static AuditListener putAuditListener(SessionFactoryImplementor sessionFactory, AuditListener listener) {
        return AUDIT_LISTENER_MAP.put(sessionFactory, listener);
    }

    public static AuditListener removeAuditListener(SessionFactoryImplementor sessionFactory) {
        return AUDIT_LISTENER_MAP.remove(sessionFactory);
    }
}

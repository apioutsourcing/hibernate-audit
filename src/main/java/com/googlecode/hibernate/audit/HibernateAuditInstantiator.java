package com.googlecode.hibernate.audit;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.hibernate.MappingException;
import org.hibernate.PropertyNotFoundException;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.property.access.internal.PropertyAccessStrategyBasicImpl;
import org.hibernate.property.access.internal.PropertyAccessStrategyFieldImpl;
import org.hibernate.property.access.spi.Getter;
import org.hibernate.property.access.spi.Setter;
import org.hibernate.proxy.HibernateProxy;

import com.googlecode.hibernate.audit.configuration.AuditConfiguration;
import com.googlecode.hibernate.audit.model.AuditEvent;
import com.googlecode.hibernate.audit.model.clazz.AuditType;
import com.googlecode.hibernate.audit.model.object.AuditObject;
import com.googlecode.hibernate.audit.model.object.EntityAuditObject;
import com.googlecode.hibernate.audit.model.property.AuditObjectProperty;
import com.googlecode.hibernate.audit.model.property.ComponentObjectProperty;
import com.googlecode.hibernate.audit.model.property.EntityObjectProperty;
import com.googlecode.hibernate.audit.model.property.SimpleObjectProperty;

public final class HibernateAuditInstantiator {

    private static final ThreadLocal<Map<EntityKey, Object>> KEY_TO_OBJECT_CONTEXT = new ThreadLocal<Map<EntityKey, Object>>() {
        @Override
        protected Map<EntityKey, Object> initialValue() {
            return new HashMap<>();
        }
    };

    private static final ThreadLocal<Map<EntityKey, Object>> DELETED_KEY_TO_OBJECT_CONTEXT = new ThreadLocal<Map<EntityKey, Object>>() {
        @Override
        protected Map<EntityKey, Object> initialValue() {
            return new HashMap<>();
        }
    };

    private HibernateAuditInstantiator() {
    }

    /**
     * Reconstruct the object from the audit log.
     *
     * @param session
     * @param auditType
     * @param externalId
     * @param transactionId
     * @return
     */
    public static Object getEntity(Session session, AuditType auditType, String externalId, Long transactionId) {
        AuditConfiguration auditConfiguration = HibernateAudit.getAuditConfiguration(session);
        KEY_TO_OBJECT_CONTEXT.get().clear();
        DELETED_KEY_TO_OBJECT_CONTEXT.get().clear();
        Object result;
        try {
            result = doGetEntity(session, auditType, externalId, transactionId, auditConfiguration);
            return result;
        } catch (ClassNotFoundException e) {
            throw new HibernateException(e);
        } finally {
            KEY_TO_OBJECT_CONTEXT.get().clear();
            DELETED_KEY_TO_OBJECT_CONTEXT.get().clear();
        }
    }

    private static Object doGetEntity(Session session, AuditType auditType, String externalId, Long transactionId, AuditConfiguration auditConfiguration) throws ClassNotFoundException {
        EntityKey topKey = new EntityKey(auditType.getClassName(), externalId);
        if (KEY_TO_OBJECT_CONTEXT.get().containsKey(topKey)) {
            return KEY_TO_OBJECT_CONTEXT.get().get(topKey);
        }

        List<AuditEvent> auditEvents = HibernateAudit.getAllAuditEventsForEntityUntilTransactionId(session, auditType, externalId, transactionId);

        for (AuditEvent event : auditEvents) {
            for (AuditObject object : event.getAuditObjects()) {
                object = instantiate(object);

                if (object instanceof EntityAuditObject) {
                    EntityAuditObject entity = (EntityAuditObject) object;

                    Object entityObject;

                    String entityName = HibernateAudit.getEntityName(auditConfiguration, session, entity.getAuditType().getClassName());
                    SessionFactoryImplementor sfi = (SessionFactoryImplementor) session.getSessionFactory();
                    EntityPersister persister = sfi.getMappingMetamodel().getEntityDescriptor(entityName);

                    if (AuditEvent.INSERT_AUDIT_EVENT_TYPE.equals(event.getType())) {
                        // identifier class
                        Class<?> idClass = persister.getIdentifierMapping().getJavaType().getJavaTypeClass();
                        Serializable id = (Serializable) auditConfiguration.getExtensionManager()
                                                                           .getPropertyValueConverter()
                                                                           .valueOf(idClass, entity.getTargetEntityId());

                        // instantiate entity (Hibernate 6: instantiate on persister)
                        entityObject = persister.instantiate(id, (SessionImplementor) session);

                        // place the object before property initialize so if
                        // there is bi-directional relationship they will be
                        // initialized correctly.
                        KEY_TO_OBJECT_CONTEXT.get().put(new EntityKey(entity.getAuditType().getClassName(), entity.getTargetEntityId()), entityObject);

                        initializeProperties(session, auditConfiguration, event, object, entityObject, persister, transactionId);
                    } else if (AuditEvent.UPDATE_AUDIT_EVENT_TYPE.equals(event.getType())
                            || AuditEvent.ADD_AUDIT_EVENT_TYPE.equals(event.getType())
                            || AuditEvent.MODIFY_AUDIT_EVENT_TYPE.equals(event.getType())
                            || AuditEvent.REMOVE_AUDIT_EVENT_TYPE.equals(event.getType())) {

                        entityObject = KEY_TO_OBJECT_CONTEXT.get().get(new EntityKey(entity.getAuditType().getClassName(), entity.getTargetEntityId()));

                        initializeProperties(session, auditConfiguration, event, object, entityObject, persister, transactionId);
                    } else if (AuditEvent.DELETE_AUDIT_EVENT_TYPE.equals(event.getType())) {
                        // need to remove all references
                        Object removedEntity = KEY_TO_OBJECT_CONTEXT.get().remove(new EntityKey(entity.getAuditType().getClassName(), entity.getTargetEntityId()));
                        DELETED_KEY_TO_OBJECT_CONTEXT.get().put(new EntityKey(entity.getAuditType().getClassName(), entity.getTargetEntityId()), removedEntity);
                    }
                }
            }
        }

        return KEY_TO_OBJECT_CONTEXT.get().get(new EntityKey(auditType.getClassName(), externalId));
    }

    /**
     * Rewritten initializeProperties for Hibernate 6.5
     *
     * Note: classMetadata parameter replaced with EntityPersister.
     */
    private static void initializeProperties(Session session, AuditConfiguration auditConfiguration, AuditEvent event,
            AuditObject object, Object entityObject, EntityPersister persister,
            Long transactionId) throws ClassNotFoundException {

        SessionFactoryImplementor sfi = (SessionFactoryImplementor) session.getSessionFactory();

        for (AuditObjectProperty property : object.getAuditObjectProperties()) {
            property = instantiate(property);

            if (property instanceof EntityObjectProperty) {
                EntityObjectProperty prop = (EntityObjectProperty) property;

                AuditType propertyFieldType = prop.getAuditType();
                Object entityValue = null;
                if (propertyFieldType != null) {
                    entityValue = doGetEntity(session, propertyFieldType, prop.getTargetEntityId(), transactionId, auditConfiguration);

                    if (entityValue == null) {
                        if (DELETED_KEY_TO_OBJECT_CONTEXT.get().get(new EntityKey(propertyFieldType.getClassName(), prop.getTargetEntityId())) == null) {
                            // the object was not detected to be deleted so
                            // check if it is a config data..

                            String entityName = HibernateAudit.getEntityName(auditConfiguration, session, propertyFieldType.getClassName());
                            Class<?> entityClass = Class.forName(entityName);
                            Class<?> idClass;
                            try {
                                EntityPersister tmpPersister = sfi.getMappingMetamodel().getEntityDescriptor(entityClass);
                                idClass = tmpPersister.getIdentifierMapping().getJavaType().getJavaTypeClass();
                                Serializable id = (Serializable) auditConfiguration.getExtensionManager().getPropertyValueConverter().valueOf(idClass,
                                        prop.getTargetEntityId());
                                entityValue = session.get(entityClass, id);
                            } catch (Exception e) {
                                // fallback: try by name lookup and Class.forName
                                Class<?> fallback = Class.forName(entityName);
                                idClass = Object.class;
                                Serializable id = (Serializable) auditConfiguration.getExtensionManager().getPropertyValueConverter().valueOf(idClass,
                                        prop.getTargetEntityId());
                                entityValue = session.get(fallback, id);
                            }
                        }
                    }
                }

                if (prop.getIndex() == null) {
                    // set property value via setter
                    Setter s = setter(entityObject.getClass(), prop.getAuditField().getName());
                    s.set(entityObject, entityValue);
                } else {
                    // collection handling
                    Getter g = getter(entityObject.getClass(), prop.getAuditField().getName());
                    Object collectionValue = g.get(entityObject);
                    Collection collection = (collectionValue instanceof Collection) ? (Collection) collectionValue : null;

                    if (collection == null) {
                        collection = new ArrayList<>();
                    }

                    if (AuditEvent.ADD_AUDIT_EVENT_TYPE.equals(event.getType())) {
                        if (DELETED_KEY_TO_OBJECT_CONTEXT.get().get(new EntityKey(propertyFieldType.getClassName(), prop.getTargetEntityId())) == null) {
                            collection.add(entityValue);
                        }
                    } else if (AuditEvent.REMOVE_AUDIT_EVENT_TYPE.equals(event.getType())) {
                        // need to remove element by id
                        Serializable deletedElementId = null;
                        try {
                            // try get element persister via target entity type
                            String elementEntityName = propertyFieldType.getClassName();
                            EntityPersister elementPersister;
                            try {
                                Class<?> elemClass = Class.forName(elementEntityName);
                                elementPersister = sfi.getMappingMetamodel().getEntityDescriptor(elemClass);
                            } catch (Exception ex) {
                                // fallback to using entityName string
                                elementPersister = sfi.getMappingMetamodel().getEntityDescriptor(elementEntityName);
                            }
                            Class<?> elemIdClass = elementPersister.getIdentifierMapping().getJavaType().getJavaTypeClass();
                            deletedElementId = (Serializable) auditConfiguration.getExtensionManager().getPropertyValueConverter().valueOf(elemIdClass, prop.getTargetEntityId());
                        } catch (Exception e) {
                            // cannot determine element id class, try treating id as string
                            deletedElementId = prop.getTargetEntityId();
                        }

                        boolean elementFound = false;
                        for (Iterator i = collection.iterator(); i.hasNext();) {
                            Object existingElement = i.next();
                            try {
                                EntityPersister elemPers = sfi.getMappingMetamodel().getEntityDescriptor(existingElement.getClass());
                                Serializable existingElementId = (Serializable)elemPers.getIdentifier(existingElement, (SessionImplementor) session);
                                if (deletedElementId != null && deletedElementId.equals(existingElementId)) {
                                    i.remove();
                                    elementFound = true;
                                    break;
                                }
                            } catch (Exception e) {
                                // if we cannot get persister/identifier, try equals on toString
                                if (deletedElementId != null && deletedElementId.equals(existingElement.toString())) {
                                    i.remove();
                                    elementFound = true;
                                    break;
                                }
                            }
                        }

                        if (!elementFound && (DELETED_KEY_TO_OBJECT_CONTEXT.get().get(new EntityKey(propertyFieldType.getClassName(), prop.getTargetEntityId())) == null)) {
                            throw new HibernateException("Unable to find entity with id " + deletedElementId + " in collection " + prop.getAuditField().getOwnerType().getClassName() + "."
                                    + prop.getAuditField().getName() + " collection");
                        }
                    }

                    // write back collection if it was null initially or modified
                    Setter s = setter(entityObject.getClass(), prop.getAuditField().getName());
                    s.set(entityObject, collection);
                }
            } else if (property instanceof ComponentObjectProperty) {
                try {
                    ComponentObjectProperty prop = (ComponentObjectProperty) property;

                    // instantiate component by class name - simpler and robust
                    Class<?> componentClass = Class.forName(prop.getAuditField().getFieldType().getClassName());
                    Object component = null;
                    try {
                        Constructor<?> constructor = org.hibernate.internal.util.ReflectHelper.getDefaultConstructor(componentClass);
                        component = constructor.newInstance((Object) null);
                    } catch (Exception nsme) {
                        component = componentClass.getDeclaredConstructor().newInstance();
                    }

                    // populate component properties
                    for (AuditObjectProperty componentProperty : prop.getTargetComponentAuditObject().getAuditObjectProperties()) {
                        if (componentProperty instanceof EntityObjectProperty) {
                            EntityObjectProperty componentProp = (EntityObjectProperty) componentProperty;
                            Object entityValue = doGetEntity(session, componentProp.getAuditField().getFieldType(), componentProp.getTargetEntityId(), transactionId, auditConfiguration);
                            Setter s = setter(component.getClass(), componentProp.getAuditField().getName());
                            s.set(component, entityValue);
                        } else if (componentProperty instanceof ComponentObjectProperty) {
                            // nested components - recursively handle or skip for now
                            // you can extend this to create nested embeddables
                        } else {
                            SimpleObjectProperty componentProp = (SimpleObjectProperty) componentProperty;
                            Object value = auditConfiguration.getExtensionManager().getPropertyValueConverter().valueOf(Class.forName(componentProp.getAuditField().getFieldType().getClassName()),
                                    componentProp.getValue());
                            Setter s = setter(component.getClass(), componentProp.getAuditField().getName());
                            s.set(component, value);
                        }
                    }

                    if (prop.getIndex() == null) {
                        Setter s = setter(entityObject.getClass(), prop.getAuditField().getName());
                        s.set(entityObject, component);
                    } else {
                        Getter g = getter(entityObject.getClass(), prop.getAuditField().getName());
                        Object collectionValue = g.get(entityObject);
                        Collection collection = (collectionValue instanceof Collection) ? (Collection) collectionValue : new ArrayList<>();

                        if (AuditEvent.ADD_AUDIT_EVENT_TYPE.equals(event.getType())) {
                            collection.add(component);
                        } else if (AuditEvent.REMOVE_AUDIT_EVENT_TYPE.equals(event.getType())) {
                            long index = 0;
                            for (Iterator i = collection.iterator(); i.hasNext(); index++) {
                                i.next();
                                if (index == prop.getIndex()) {
                                    i.remove();
                                    break;
                                }
                            }
                        }

                        Setter s = setter(entityObject.getClass(), prop.getAuditField().getName());
                        s.set(entityObject, collection);
                    }
                } catch (InstantiationException e) {
                    throw new HibernateException(e);
                } catch (InvocationTargetException e) {
                    throw new HibernateException(e);
                } catch (IllegalAccessException e) {
                    throw new HibernateException(e);
                } catch (NoSuchMethodException e) {
                    throw new HibernateException(e);
                }
            } else {
                SimpleObjectProperty prop = (SimpleObjectProperty) property;
                Object value = null;
                if (prop.getValue() != null) {
                    value = auditConfiguration.getExtensionManager().getPropertyValueConverter().valueOf(Class.forName(prop.getAuditType().getClassName()), prop.getValue());
                }
                if (prop.getIndex() == null) {
                    Setter s = setter(entityObject.getClass(), prop.getAuditField().getName());
                    s.set(entityObject, value);
                } else {
                    Getter g = getter(entityObject.getClass(), prop.getAuditField().getName());
                    Object collectionValue = g.get(entityObject);
                    if (collectionValue instanceof Collection) {
                        Collection coll = (Collection) collectionValue;
                        if (AuditEvent.ADD_AUDIT_EVENT_TYPE.equals(event.getType())) {
                            coll.add(value);
                        } else if (AuditEvent.REMOVE_AUDIT_EVENT_TYPE.equals(event.getType())) {
                            long index = 0;
                            for (Iterator i = coll.iterator(); i.hasNext(); index++) {
                                i.next();
                                if (index == prop.getIndex()) {
                                    i.remove();
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static AuditObject instantiate(AuditObject object) {
        Hibernate.initialize(object);

        if (object instanceof HibernateProxy) {
            object = (AuditObject) ((HibernateProxy) object).getHibernateLazyInitializer().getImplementation();
        }
        return object;
    }

    private static AuditObjectProperty instantiate(AuditObjectProperty object) {
        Hibernate.initialize(object);

        if (object instanceof HibernateProxy) {
            object = (AuditObjectProperty) ((HibernateProxy) object).getHibernateLazyInitializer().getImplementation();
        }
        return object;
    }

    private static Setter setter(Class<?> clazz, String name) throws MappingException {
        try {
            return PropertyAccessStrategyBasicImpl.INSTANCE.buildPropertyAccess(clazz, name, false).getSetter();
        } catch (PropertyNotFoundException pnfe) {
            return PropertyAccessStrategyFieldImpl.INSTANCE.buildPropertyAccess(clazz, name, false).getSetter();
        }
    }

    private static Getter getter(Class<?> clazz, String name) throws MappingException {
        try {
            return PropertyAccessStrategyBasicImpl.INSTANCE.buildPropertyAccess(clazz, name, false).getGetter();
        } catch (PropertyNotFoundException pnfe) {
            return PropertyAccessStrategyFieldImpl.INSTANCE.buildPropertyAccess(clazz, name, false).getGetter();
        }
    }

    private static class EntityKey {
        private String className;
        private String id;

        public EntityKey(String className, String id) {
            this.className = className;
            this.id = id;
        }

        public String getClassName() {
            return className;
        }

        public String getId() {
            return id;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof EntityKey)) {
                return false;
            }
            return className.equals(((EntityKey) obj).getClassName()) && id.equals(((EntityKey) obj).getId());
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 31 * hash + (null == className ? 0 : className.hashCode());
            hash = 31 * hash + (null == id ? 0 : id.hashCode());

            return hash;
        }
    }

}

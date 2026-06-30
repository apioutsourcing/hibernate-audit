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
package com.googlecode.hibernate.audit.synchronization;

import java.security.Principal;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.action.spi.BeforeTransactionCompletionProcess;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.resource.jdbc.spi.PhysicalConnectionHandlingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.hibernate.audit.configuration.AuditConfiguration;
import com.googlecode.hibernate.audit.model.AuditLogicalGroup;
import com.googlecode.hibernate.audit.model.AuditTransaction;
import com.googlecode.hibernate.audit.model.AuditTransactionAttribute;
import com.googlecode.hibernate.audit.synchronization.work.AuditWorkUnit;

public class AuditProcess implements BeforeTransactionCompletionProcess {
	private static final Logger log = LoggerFactory.getLogger(AuditProcess.class);

	private final Session auditedSession;
	private LinkedList<AuditWorkUnit> workUnits = new LinkedList<AuditWorkUnit>();
	private AuditConfiguration auditConfiguration;

	public AuditProcess(AuditConfiguration auditConfiguration, Session session) {
		this.auditedSession = session;
		this.auditConfiguration = auditConfiguration;
	}

	public void addWorkUnit(AuditWorkUnit workUnit) {
		workUnit.init(auditedSession, auditConfiguration);
		workUnits.add(workUnit);
	}

	public void doBeforeTransactionCompletion(SessionImplementor session) {
		if (workUnits.size() == 0) {
			return;
		}

		if (!session.getTransactionCoordinator().isActive()) {
			log.debug("Skipping hibernate-audit transaction hook due to non-active (most likely marked as rollback) transaction");
			return;
		}

		if (FlushMode.MANUAL == session.getHibernateFlushMode() || session.isClosed()) {
			Session temporarySession = null;
			try {
                temporarySession = session.sessionWithOptions()
                    .connection()
                    .autoClose(false)
                    .connectionHandlingMode(PhysicalConnectionHandlingMode.DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION)
                    .openSession();
				executeInSession(temporarySession);
				temporarySession.flush();
			} finally {
				if (temporarySession != null) {
					temporarySession.close();
				}
			}
		} else {
			executeInSession(session);

            // explicitly flushing the session, as the auto-flush may have already happened.
            session.flush();
        }
	}

	private void executeInSession(Session session) {
		if (log.isDebugEnabled()) {
			log.debug("executeInSession begin");
		}

		try {
			AuditWorkUnit workUnit;
			SortedSet<AuditLogicalGroup> auditLogicalGroups = new TreeSet<AuditLogicalGroup>(new Comparator<AuditLogicalGroup>() {
				// sort audit logical groups in order to minimize
				// database dead lock conditions.
				public int compare(AuditLogicalGroup o1, AuditLogicalGroup o2) {
					// note that both entities should already be
					// persistent so they must have ids
					return o1.getId().compareTo(o2.getId());
				};
			});

			AuditTransaction auditTransaction = new AuditTransaction();
			auditTransaction.setTimestamp(new Date());
			Principal principal = auditConfiguration.getExtensionManager().getSecurityInformationProvider().getPrincipal();
			auditTransaction.setUsername(principal == null ? null : principal.getName());

			if (log.isDebugEnabled()) {
				log.debug("start workUnits perform");
			}
			while ((workUnit = workUnits.poll()) != null) {
				workUnit.perform(session, auditConfiguration, auditTransaction);
				auditLogicalGroups.addAll(workUnit.getAuditLogicalGroups());
			}
			if (log.isDebugEnabled()) {
				log.debug("end workUnits perform");
			}

			List<AuditTransactionAttribute> attributes = auditConfiguration.getExtensionManager().getAuditTransactionAttributeProvider().getAuditTransactionAttributes(session);

			if (attributes != null && !attributes.isEmpty()) {
				for (AuditTransactionAttribute attribute : attributes) {
					attribute.setAuditTransaction(auditTransaction);
				}
				auditTransaction.getAuditTransactionAttributes().addAll(attributes);
			}

			concurrencyModificationCheck(session, auditLogicalGroups, auditTransaction);

			session.persist(auditTransaction);
			for (AuditLogicalGroup storedAuditLogicalGroup : auditLogicalGroups) {
				storedAuditLogicalGroup.setLastUpdatedAuditTransactionId(auditTransaction.getId());
			}
		} finally {
			if (log.isDebugEnabled()) {
				log.debug("executeInSession end");
			}
		}
	}

	private void concurrencyModificationCheck(Session session, SortedSet<AuditLogicalGroup> auditLogicalGroups,
			AuditTransaction auditTransaction) {
		Long loadAuditTransactionId = auditConfiguration.getExtensionManager().getConcurrentModificationProvider().getLoadAuditTransactionId();
		auditConfiguration.getExtensionManager().getConcurrentModificationCheckProvider().concurrentModificationCheck(auditConfiguration, session, auditLogicalGroups, auditTransaction, loadAuditTransactionId);
	}
}
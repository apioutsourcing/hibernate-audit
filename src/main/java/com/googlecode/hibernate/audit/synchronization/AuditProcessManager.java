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

import com.googlecode.hibernate.audit.configuration.AuditConfiguration;
import com.googlecode.hibernate.audit.util.ConcurrentReferenceHashMap;
import org.hibernate.Transaction;
import org.hibernate.action.spi.AfterTransactionCompletionProcess;
import org.hibernate.action.spi.BeforeTransactionCompletionProcess;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.spi.EventSource;

import java.util.Map;

public final class AuditProcessManager {
    private final Map<Transaction, AuditProcess> auditProcesses = new ConcurrentReferenceHashMap<Transaction, AuditProcess>(16, ConcurrentReferenceHashMap.ReferenceType.WEAK,
            ConcurrentReferenceHashMap.ReferenceType.STRONG);

    private AuditConfiguration auditConfiguration;

    public AuditProcessManager(AuditConfiguration auditConfiguration) {
        this.auditConfiguration = auditConfiguration;
    }

    public AuditProcess get(EventSource session) {
        final Transaction transaction = session.accessTransaction();

        AuditProcess auditProcess = auditProcesses.get( transaction );
        if ( auditProcess == null ) {
            auditProcess = new AuditProcess( auditConfiguration, session );
            auditProcesses.put(transaction, auditProcess);

            // Hibernate 7.2 replaced ActionQueue.registerProcess with registerCallback.
            session.getActionQueue().registerCallback(new BeforeTransactionCompletionProcess() {
                @Override
                public void doBeforeTransactionCompletion(SharedSessionContractImplementor session) {
                    AuditProcess process = auditProcesses.get(transaction);
                    if (process != null) {
                        process.doBeforeTransactionCompletion(session);
                    }
                }
            });

            session.getActionQueue().registerCallback(new AfterTransactionCompletionProcess() {
                @Override
                public void doAfterTransactionCompletion(boolean success, SharedSessionContractImplementor session) {
                    auditProcesses.remove(transaction);
                }
            });
        }

        return auditProcess;
    }
}

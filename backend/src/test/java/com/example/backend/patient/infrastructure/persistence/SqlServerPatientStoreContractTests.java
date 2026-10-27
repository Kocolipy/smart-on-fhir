package com.example.backend.patient.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.database.ConnectionQualifiers;
import com.example.backend.patient.PatientStoreContract;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientName;
import com.example.backend.patient.domain.PatientStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The production patient port — the JPA adapter over SQL Server — holds to the rules the in-memory
 * store does, and one rule no in-memory store models: a refused insert leaves its transaction
 * unable to commit.
 *
 * <p>Every call runs in a transaction of its own, which commits, as a seeding or a read does in
 * production. It shares the plain cached context {@code PatientStorageIntegrationTests} carries;
 * the application login may insert a patient but never remove one, so the owner login removes
 * every row a case wrote, as that class does.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class SqlServerPatientStoreContractTests extends PatientStoreContract {

    @Autowired
    private PatientStore adapter;

    @Autowired
    @Qualifier(ConnectionQualifiers.MAIN_TRANSACTIONS)
    private PlatformTransactionManager transactions;

    @Autowired
    private DatabaseLogins logins;

    /** Every id a case asked to insert under, whether or not the insert was refused. */
    private final List<PatientId> written = new ArrayList<>();

    @AfterEach
    void removeWhatThisCaseWrote() {
        for (PatientId id : written) {
            logins.owner().jdbc().update("DELETE FROM patients WHERE id = ?", id.value());
        }
    }

    @Override
    protected PatientStore patients() {
        TransactionTemplate each = new TransactionTemplate(transactions);
        return new PatientStore() {

            @Override
            public Optional<Patient> findById(PatientId id) {
                return each.execute(status -> adapter.findById(id));
            }

            @Override
            public boolean existsById(PatientId id) {
                return Boolean.TRUE.equals(each.execute(status -> adapter.existsById(id)));
            }

            @Override
            public void insert(Patient patient) {
                written.add(patient.id());
                each.executeWithoutResult(status -> adapter.insert(patient));
            }
        };
    }

    /**
     * Code that catches the refusal and carries on — as a store that only throws would let it —
     * commits nothing on SQL Server: the violation marked the transaction rollback-only, so the
     * commit fails and the patient inserted before it in the same transaction is gone too.
     */
    @Test
    void a_refused_insert_leaves_its_transaction_unable_to_commit() {
        Patient first = patient(new PatientName("First", null, List.of()));
        Patient sameNric = second(freshId(), first.nric());
        written.add(first.id());
        written.add(sameNric.id());

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> {
                    adapter.insert(first);
                    try {
                        adapter.insert(sameNric);
                    } catch (DataIntegrityViolationException refused) {
                        // carried on past, as catch-and-continue code would
                    }
                }))
                .isInstanceOf(UnexpectedRollbackException.class);
        assertThat(patients().existsById(first.id())).isFalse();
    }
}

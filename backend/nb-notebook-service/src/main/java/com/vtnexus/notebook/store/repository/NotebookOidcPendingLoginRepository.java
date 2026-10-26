package com.vtnexus.notebook.store.repository;

import com.vtnexus.notebook.store.model.NotebookOidcPendingLogin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface NotebookOidcPendingLoginRepository extends JpaRepository<NotebookOidcPendingLogin, String> {

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("DELETE FROM NotebookOidcPendingLogin p WHERE p.state = :state")
    int deleteByState(@Param("state") String state);

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("DELETE FROM NotebookOidcPendingLogin p WHERE p.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}

package com.vtnexus.notebook.store.repository;

import com.vtnexus.notebook.store.model.NotebookCell;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotebookCellRepository extends JpaRepository<NotebookCell, String> {
    List<NotebookCell> findByNotebookIdOrderByPositionAsc(String notebookId);
    Optional<NotebookCell> findByIdAndNotebookId(String id, String notebookId);
    
    @Query("SELECT MAX(c.position) FROM NotebookCell c WHERE c.notebook.id = :notebookId")
    Integer findMaxPositionByNotebookId(@Param("notebookId") String notebookId);

    @Modifying
    @Query("UPDATE NotebookCell c SET c.position = c.position + 1 WHERE c.notebook.id = :notebookId AND c.position >= :position")
    void shiftPositionsDown(@Param("notebookId") String notebookId, @Param("position") int position);
}

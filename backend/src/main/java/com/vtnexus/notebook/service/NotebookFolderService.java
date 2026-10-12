package com.vtnexus.notebook.service;

import com.vtnexus.notebook.exception.NotebookApiException;
import com.vtnexus.notebook.model.NotebookFolder;
import com.vtnexus.notebook.repository.NotebookFolderRepository;
import com.vtnexus.notebook.repository.NotebookRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class NotebookFolderService {

    private final NotebookFolderRepository folderRepository;
    private final NotebookRepository notebookRepository;

    public NotebookFolderService(NotebookFolderRepository folderRepository, NotebookRepository notebookRepository) {
        this.folderRepository = folderRepository;
        this.notebookRepository = notebookRepository;
    }

    public NotebookFolder createFolder(String name, String parentId, String owner) {
        if (name == null || name.isBlank()) {
            throw new NotebookApiException("INVALID_REQUEST", "Folder name is required", HttpStatus.BAD_REQUEST);
        }
        if (parentId != null && !parentId.isBlank()) {
            folderRepository.findById(parentId).orElseThrow(() ->
                    new NotebookApiException("FOLDER_NOT_FOUND", "Parent folder not found", HttpStatus.NOT_FOUND));
        }

        NotebookFolder folder = NotebookFolder.builder()
                .id(UUID.randomUUID().toString())
                .name(name.trim())
                .parentId((parentId != null && !parentId.isBlank()) ? parentId : null)
                .owner(owner)
                .version(1L)
                .build();

        return folderRepository.save(folder);
    }

    public List<NotebookFolder> listFolders(String parentId, String owner) {
        if (parentId == null) {
            return folderRepository.findByOwner(owner);
        } else if (parentId.isEmpty()) {
            return folderRepository.findByOwnerAndParentIdIsNull(owner);
        } else {
            return folderRepository.findByOwnerAndParentId(owner, parentId);
        }
    }

    public NotebookFolder getFolder(String folderId, String owner) {
        NotebookFolder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new NotebookApiException("FOLDER_NOT_FOUND", "Folder not found: " + folderId, HttpStatus.NOT_FOUND));
        if (!folder.getOwner().equals(owner)) {
            throw new NotebookApiException("FOLDER_NOT_FOUND", "Folder not found: " + folderId, HttpStatus.NOT_FOUND);
        }
        return folder;
    }

    public NotebookFolder updateFolder(String folderId, String name, String parentId, Long version, String owner) {
        NotebookFolder folder = getFolder(folderId, owner);

        if (version != null && !folder.getVersion().equals(version)) {
            throw new NotebookApiException("VERSION_CONFLICT", "Folder was modified concurrently", HttpStatus.CONFLICT);
        }

        if (name != null && !name.isBlank()) {
            folder.setName(name.trim());
        }

        if (parentId != null) {
            if (parentId.isEmpty()) {
                folder.setParentId(null);
            } else {
                if (parentId.equals(folderId)) {
                    throw new NotebookApiException("INVALID_REQUEST", "Cannot set parent folder to itself", HttpStatus.BAD_REQUEST);
                }
                folderRepository.findById(parentId).orElseThrow(() ->
                        new NotebookApiException("FOLDER_NOT_FOUND", "Target parent folder not found", HttpStatus.NOT_FOUND));
                folder.setParentId(parentId);
            }
        }

        folder.setVersion(folder.getVersion() + 1);
        return folderRepository.save(folder);
    }

    public void deleteFolder(String folderId, Long version, String owner) {
        NotebookFolder folder = getFolder(folderId, owner);
        if (version != null && !folder.getVersion().equals(version)) {
            throw new NotebookApiException("VERSION_CONFLICT", "Folder was modified concurrently", HttpStatus.CONFLICT);
        }
        deleteFolderCascade(folderId);
    }

    private void deleteFolderCascade(String folderId) {
        List<NotebookFolder> children = folderRepository.findByParentId(folderId);
        for (NotebookFolder child : children) {
            deleteFolderCascade(child.getId());
        }
        folderRepository.deleteById(folderId);
    }
}

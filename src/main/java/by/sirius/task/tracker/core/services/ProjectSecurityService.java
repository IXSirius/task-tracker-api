package by.sirius.task.tracker.core.services;

import by.sirius.task.tracker.api.exceptions.BadRequestException;
import by.sirius.task.tracker.core.services.helpers.ServiceHelper;
import by.sirius.task.tracker.store.entities.*;
import by.sirius.task.tracker.store.repositories.ProjectRepository;
import by.sirius.task.tracker.store.repositories.ProjectRoleRepository;
import by.sirius.task.tracker.store.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectSecurityService {

    private final ServiceHelper serviceHelper;
    private final ProjectRoleRepository projectRoleRepository;

    public boolean hasProjectPermission(Long projectId, String permissionType) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        UserEntity currentUser = serviceHelper.getUserOrThrowException(currentUsername);
        ProjectEntity project = serviceHelper.getProjectOrThrowException(projectId);

        return checkPermissions(permissionType, currentUser, project);
    }

    public boolean hasTaskStatePermission(Long taskStateId, String permissionType) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        UserEntity currentUser = serviceHelper.getUserOrThrowException(currentUsername);
        TaskStateEntity taskState = serviceHelper.getTaskStateOrThrowException(taskStateId);
        ProjectEntity project = taskState.getProject();

        return checkPermissions(permissionType, currentUser, project);
    }

    public boolean hasTaskPermission(Long taskId, String permissionType) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        UserEntity currentUser = serviceHelper.getUserOrThrowException(currentUsername);
        TaskEntity taskEntity = serviceHelper.getTaskOrThrowException(taskId);
        TaskStateEntity taskState = taskEntity.getTaskState();
        ProjectEntity project = taskState.getProject();

        return checkPermissions(permissionType, currentUser, project);
    }

    public boolean isAdminOfProject(Long projectId, String username) {
        UserEntity user = serviceHelper.getUserOrThrowException(username);
        ProjectEntity project = serviceHelper.getProjectOrThrowException(projectId);

        ProjectRoleEntity projectUserRole = projectRoleRepository.findByUserAndProject(user, project)
                .orElseThrow(() -> new BadRequestException("No permissions", HttpStatus.UNAUTHORIZED));

        return projectUserRole.getRole().getName().equals("ROLE_ADMIN");
    }

    private boolean checkPermissions(String permissionType, UserEntity currentUser, ProjectEntity project) {
        ProjectRoleEntity projectUserRole = projectRoleRepository.findByUserAndProject(currentUser, project)
                .orElseThrow(() -> new BadRequestException("No permissions", HttpStatus.UNAUTHORIZED));

        if (permissionType.equals("WRITE") && projectUserRole.getRole().getName().equals("ROLE_ADMIN")) {
            return true;
        }

        if (permissionType.equals("READ")) {
            return true;
        }

        return false;
    }
}

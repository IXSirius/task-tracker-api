package by.sirius.task.tracker.api.services;

import by.sirius.task.tracker.api.exceptions.BadRequestException;
import by.sirius.task.tracker.store.entities.*;
import by.sirius.task.tracker.store.repositories.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectSecurityService {

    private final UserRepository userRepository;
    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final TaskStateRepository taskStateRepository;
    private final ProjectRoleRepository projectRoleRepository;

    public boolean hasProjectPermission(Long projectId, String permissionType) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        UserEntity currentUser = userRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new BadRequestException("User not found", HttpStatus.BAD_REQUEST));

        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BadRequestException("Project not found", HttpStatus.BAD_REQUEST));

        return checkPermissions(permissionType, currentUser, project);
    }

    public boolean hasTaskStatePermission(Long taskStateId, String permissionType) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        UserEntity currentUser = userRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new BadRequestException("User not found", HttpStatus.BAD_REQUEST));

        TaskStateEntity taskState = taskStateRepository.findById(taskStateId)
                .orElseThrow(() -> new BadRequestException("Task state not found", HttpStatus.BAD_REQUEST));

        ProjectEntity project = taskState.getProject();

        return checkPermissions(permissionType, currentUser, project);
    }

    public boolean hasTaskPermission(Long taskId, String permissionType) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        UserEntity currentUser = userRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new BadRequestException("User not found", HttpStatus.BAD_REQUEST));

        TaskEntity taskEntity = taskRepository.findById(taskId)
                .orElseThrow(() -> new BadRequestException("Task not found", HttpStatus.BAD_REQUEST));

        TaskStateEntity taskState = taskEntity.getTaskStateEntity();

        ProjectEntity project = taskState.getProject();

        return checkPermissions(permissionType, currentUser, project);
    }

    private boolean checkPermissions(String permissionType, UserEntity currentUser, ProjectEntity project) {
        ProjectRoleEntity projectUserRole = projectRoleRepository.findByUserAndProject(currentUser, project)
                .orElseThrow(() -> new BadRequestException("No permissions", HttpStatus.BAD_REQUEST));

        if (permissionType.equals("WRITE") && projectUserRole.getRole().getName().equals("ROLE_ADMIN")) {
            return true;
        }
        if (permissionType.equals("READ")) {
            return true;
        }

        return false;
    }
}
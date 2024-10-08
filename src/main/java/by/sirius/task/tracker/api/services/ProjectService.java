package by.sirius.task.tracker.api.services;

import by.sirius.task.tracker.api.dto.AckDto;
import by.sirius.task.tracker.api.dto.ProjectDto;
import by.sirius.task.tracker.api.exceptions.BadRequestException;
import by.sirius.task.tracker.api.factories.ProjectDtoFactory;
import by.sirius.task.tracker.api.services.helpers.ServiceHelper;
import by.sirius.task.tracker.store.entities.ProjectEntity;
import by.sirius.task.tracker.store.entities.RoleEntity;
import by.sirius.task.tracker.store.entities.UserEntity;
import by.sirius.task.tracker.store.repositories.ProjectRepository;
import by.sirius.task.tracker.store.repositories.RoleRepository;
import by.sirius.task.tracker.store.repositories.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RequiredArgsConstructor
@Transactional
@Service
public class ProjectService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ProjectRepository projectRepository;
    private final ProjectDtoFactory projectDtoFactory;

    private final ServiceHelper serviceHelper;

    public List<ProjectDto> fetchProjects(Optional<String> optionalPrefixName) {

        optionalPrefixName = optionalPrefixName.filter(prefixName -> !prefixName.trim().isEmpty());

        Stream<ProjectEntity> projectStream = optionalPrefixName
                .map(projectRepository::streamAllByNameStartsWithIgnoreCase)
                .orElseGet(projectRepository::streamAllBy);

        return projectStream
                .map(projectDtoFactory::makeProjectDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProjectDto createProject(String name) {

        if (name.trim().isEmpty()) {
            throw new BadRequestException("Name can't be empty", HttpStatus.BAD_REQUEST);
        }

        projectRepository
                .findByName(name)
                .ifPresent(project -> {
                    throw new BadRequestException(String.format("Project \"%s\" already exists.", name), HttpStatus.BAD_REQUEST);
                });

        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        UserEntity admin = userRepository.findByUsername(currentUsername)
                .orElseThrow(() -> (new BadRequestException("User not found", HttpStatus.BAD_REQUEST)));

        ProjectEntity project = projectRepository.saveAndFlush(
                ProjectEntity.builder()
                        .name(name)
                        .admin(admin)
                        .build()
        );

        return projectDtoFactory.makeProjectDto(project);
    }

    @Transactional
    public ProjectDto editProject(Long projectId, String name) {

        if (name.trim().isEmpty()) {
            throw new BadRequestException("Name can't be empty", HttpStatus.BAD_REQUEST);
        }

        ProjectEntity project = serviceHelper.getProjectOrThrowException(projectId);

        projectRepository
                .findByName(name)
                .filter(anotherProject -> !Objects.equals(anotherProject.getId(), projectId))
                .ifPresent(anotherProject -> {
                    throw new BadRequestException(String.format("Project \"%s\" already exists.", name), HttpStatus.BAD_REQUEST);
                });

        project.setName(name);
        project = projectRepository.saveAndFlush(project);

        return projectDtoFactory.makeProjectDto(project);
    }

    @Transactional
    public AckDto deleteProject(Long projectId) {

        serviceHelper.getProjectOrThrowException(projectId);

        projectRepository.deleteById(projectId);

        return AckDto.makeDefault(true);
    }

    @Transactional
    public AckDto removeUserFromProject(Long projectId, String username, String adminUsername) {

        ProjectEntity project = projectRepository.getProjectById(projectId)
                .orElseThrow(() -> new BadRequestException("Project not found", HttpStatus.BAD_REQUEST));

        if(!project.getAdmin().getUsername().equals(adminUsername)) {
            throw new BadRequestException("Only the project admin can remove users", HttpStatus.BAD_REQUEST);
        }

        UserEntity userToDelete = userRepository.findByUsername(username)
                .orElseThrow(() -> new BadRequestException("User not found", HttpStatus.BAD_REQUEST));

        project.getUsers().remove(userToDelete);
        userToDelete.getMemberProjects().remove(project);

        projectRepository.save(project);
        userRepository.save(userToDelete);

        if (userToDelete.getMemberProjects().isEmpty()) {
            RoleEntity userRole = roleRepository.findByName("ROLE_USER")
                    .orElseThrow(() -> new BadRequestException("Role not found", HttpStatus.BAD_REQUEST));
            userToDelete.getRoles().remove(userRole);
        }

        return AckDto.builder().answer(true).build();
    }

    public ProjectEntity getProjectById(Long projectId) {
        ProjectEntity project = projectRepository.getProjectById(projectId)
                .orElseThrow(() -> new BadRequestException("Project not found", HttpStatus.BAD_REQUEST));
        return project;
    }

    public boolean isAdmin(UserEntity user, ProjectEntity project) {
        return project.getAdmin().equals(user);
    }

    public boolean isMember(UserEntity user, ProjectEntity project) {
        return project.getUsers().contains(user);
    }
}

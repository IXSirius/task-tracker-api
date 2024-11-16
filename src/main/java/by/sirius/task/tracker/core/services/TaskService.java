package by.sirius.task.tracker.core.services;

import by.sirius.task.tracker.api.dto.AckDto;
import by.sirius.task.tracker.api.dto.TaskDto;
import by.sirius.task.tracker.api.exceptions.BadRequestException;
import by.sirius.task.tracker.api.exceptions.NotFoundException;
import by.sirius.task.tracker.core.factories.TaskDtoFactory;
import by.sirius.task.tracker.core.services.helpers.ServiceHelper;
import by.sirius.task.tracker.store.entities.*;
import by.sirius.task.tracker.store.repositories.TaskHistoryRepository;
import by.sirius.task.tracker.store.repositories.TaskRepository;
import by.sirius.task.tracker.store.repositories.TaskStateRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
@Service
public class TaskService {

    private final EmailService emailService;
    private final TaskDtoFactory taskDtoFactory;
    private final TaskRepository taskRepository;
    private final TaskStateRepository taskStateRepository;
    private final TaskHistoryRepository taskHistoryRepository;

    private final ServiceHelper serviceHelper;

    @Cacheable(value = "tasks", key = "#projectId + '-' + #taskStateId")
    public List<TaskDto> getTasks(Long projectId, Long taskStateId) {
        log.debug("Fetching tasks for project ID: {} and task state ID: {}", projectId, taskStateId);

        Optional<TaskStateEntity> taskState = taskStateRepository.findByProjectIdAndId(projectId, taskStateId);

        return taskState
                .map(state -> state.getTasks()
                        .stream()
                        .map(taskDtoFactory::makeTaskDto)
                        .collect(Collectors.toList()))
                .orElseThrow(() -> new NotFoundException(
                        String.format("Task state with id \"%d\" not found", taskStateId), HttpStatus.BAD_REQUEST));
    }

    @Cacheable(value = "tasks", key = "#username")
    public List<TaskDto> getAssignedTasks(String username) {
        log.debug("Getting assigned for project user: {}", username);

        UserEntity user = serviceHelper.getUserOrThrowException(username);
        List<TaskEntity> assignedTasks = taskRepository.findByAssignedUser(user);

        return assignedTasks.stream()
                .map(taskDtoFactory::makeTaskDto)
                .collect(Collectors.toList());
    }

    @CacheEvict(value = "tasks", key = "#projectId + '-' + #taskStateId")
    @Transactional
    public TaskDto createTask(Long projectId, Long taskStateId, String taskName) {
        log.info("Creating task '{}' in project ID: {} and task state ID: {}", taskName, projectId, taskStateId);

        if (taskName.isBlank()) {
            throw new BadRequestException("Task name can't be empty", HttpStatus.BAD_REQUEST);
        }

        ProjectEntity project = serviceHelper.getProjectOrThrowException(projectId);
        TaskStateEntity taskState = serviceHelper.getTaskStateOrThrowException(taskStateId);

        Optional<TaskEntity> optionalAnotherTask = Optional.empty();

        if(!project.getTaskStates().contains(taskState)) {
            throw new NotFoundException("Project doesn't contain a such task state", HttpStatus.NOT_FOUND);
        }

        for (TaskEntity task : taskState.getTasks()) {
            if (task.getName().equals(taskName)) {
                throw new BadRequestException(
                        String.format("Task name \"%s\" already exists.", taskName), HttpStatus.BAD_REQUEST);
            }
            if (task.getRightTask().isEmpty()) {
                optionalAnotherTask = Optional.of(task);
            }
        }

        TaskEntity task = TaskEntity.builder()
                .name(taskName)
                .taskState(taskState)
                .build();

        taskState.getTasks().add(task);

        taskRepository.save(task);

        optionalAnotherTask.ifPresent(it -> {
            task.setLeftTask(it);
            it.setRightTask(task);
            taskRepository.save(it);
        });

        taskStateRepository.save(taskState);

        return taskDtoFactory.makeTaskDto(task);
    }

    @CacheEvict(value = "tasks", key = "#taskId")
    @Transactional
    public TaskDto editTask(Long taskId, String taskName) {
        log.info("Editing task ID: {}, new name: {}", taskId, taskName);
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        if (taskName.isBlank()) {
            throw new BadRequestException("Task name can't be empty", HttpStatus.BAD_REQUEST);
        }

        TaskEntity taskToUpdate = serviceHelper.getTaskOrThrowException(taskId);
        Long taskStateId = taskToUpdate.getTaskState().getId();

        taskRepository
                .findByTaskStateEntityIdAndNameIgnoreCase(taskStateId, taskName)
                .filter(existingTask -> !existingTask.getId().equals(taskId))
                .ifPresent(existingTask -> {
                    throw new BadRequestException(
                            String.format("Task \"%s\" already exists in this task state", taskName), HttpStatus.BAD_REQUEST);
                });

        String oldTaskName = taskToUpdate.getName();
        taskToUpdate.setName(taskName);

        TaskEntity updatedTask = taskRepository.save(taskToUpdate);

        TaskHistoryEntity taskHistory = TaskHistoryEntity.builder()
                .task(updatedTask)
                .username(currentUsername)
                .changeType("EDIT")
                .fieldName("name")
                .oldValue(oldTaskName)
                .newValue(updatedTask.getName())
                .build();

        taskHistoryRepository.save(taskHistory);

        return taskDtoFactory.makeTaskDto(updatedTask);
    }

    @CacheEvict(value = "tasks", key = "#taskId")
    @Transactional
    public AckDto deleteTask(Long taskId) {
        log.warn("Deleting task with ID: {}", taskId);
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        TaskEntity taskToDelete = serviceHelper.getTaskOrThrowException(taskId);
        serviceHelper.replaceOldTaskPosition(taskToDelete);

        TaskStateEntity taskState = taskToDelete.getTaskState();
        taskState.getTasks().remove(taskToDelete);

        TaskHistoryEntity taskHistory = TaskHistoryEntity.builder()
                .task(taskToDelete)
                .username(currentUsername)
                .changeType("DELETE")
                .build();

        taskHistoryRepository.save(taskHistory);
        taskStateRepository.save(taskState);
        taskRepository.delete(taskToDelete);

        return AckDto.builder().answer(true).build();
    }

    @CacheEvict(value = "tasks", key = "#taskId")
    @Transactional
    public TaskDto changeTaskPosition(Long taskId, Optional<Long> optionalLeftTaskId) {
        log.info("Changing task position for task ID: {} with left task ID: {}", taskId, optionalLeftTaskId.orElse(null));
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        TaskEntity changeTask = serviceHelper.getTaskOrThrowException(taskId);
        TaskStateEntity taskState = changeTask.getTaskState();

        Optional<Long> optionalOldLeftTaskId = changeTask
                .getLeftTask()
                .map(TaskEntity::getId);

        if (optionalOldLeftTaskId.equals(optionalLeftTaskId)) {
            return taskDtoFactory.makeTaskDto(changeTask);
        }

        Optional<TaskEntity> optionalNewLeftTask = optionalLeftTaskId
                .map(leftTaskId -> {

                    if (taskId.equals(leftTaskId)) {
                        throw new BadRequestException(
                                "Left task id equals changed task", HttpStatus.BAD_REQUEST);
                    }

                    TaskEntity leftTaskEntity = serviceHelper.getTaskOrThrowException(leftTaskId);

                    if (!taskState.getId().equals(leftTaskEntity.getTaskState().getId())) {
                        throw new BadRequestException(
                                "Task position can be changed within the same task state", HttpStatus.BAD_REQUEST);
                    }

                    return leftTaskEntity;
                });

        Optional<TaskEntity> optionalNewRightTask;
        if (optionalNewLeftTask.isEmpty()) {
            optionalNewRightTask = taskState
                    .getTasks()
                    .stream()
                    .filter(anotherTask -> anotherTask.getLeftTask().isEmpty())
                    .findAny();
        } else {
            optionalNewRightTask = optionalNewLeftTask
                    .get()
                    .getRightTask();
        }

        serviceHelper.replaceOldTaskPosition(changeTask);

        if (optionalNewLeftTask.isPresent()) {
            TaskEntity newLeftTask = optionalNewLeftTask.get();
            newLeftTask.setRightTask(changeTask);
            changeTask.setLeftTask(newLeftTask);
        } else {
            changeTask.setLeftTask(null);
        }

        if (optionalNewRightTask.isPresent()) {
            TaskEntity newRightTask = optionalNewRightTask.get();
            newRightTask.setLeftTask(changeTask);
            changeTask.setRightTask(newRightTask);
        } else {
            changeTask.setRightTask(null);
        }

        changeTask = taskRepository.saveAndFlush(changeTask);

        optionalNewLeftTask
                .ifPresent(taskRepository::saveAndFlush);

        optionalNewRightTask
                .ifPresent(taskRepository::saveAndFlush);

        taskStateRepository.save(taskState);

        TaskHistoryEntity taskHistory = TaskHistoryEntity.builder()
                .task(changeTask)
                .username(currentUsername)
                .changeType("EDIT")
                .fieldName("task position")
                .oldValue(optionalOldLeftTaskId.map(String::valueOf).orElse(null))
                .newValue(optionalNewLeftTask.map(task -> String.valueOf(task.getId())).orElse(null))
                .build();

        taskHistoryRepository.save(taskHistory);

        return taskDtoFactory.makeTaskDto(changeTask);
    }

    @CacheEvict(value = "tasks", key = "#taskId")
    @Transactional
    public TaskDto changeTaskState(Long taskId, Long newTaskStateId) {
        log.info("Changing task state for task ID: {} to new task state ID: {}", taskId, newTaskStateId);
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        TaskEntity taskToMove = serviceHelper.getTaskOrThrowException(taskId);
        TaskStateEntity newTaskState = serviceHelper.getTaskStateOrThrowException(newTaskStateId);
        TaskStateEntity currentTaskState = taskToMove.getTaskState();

        newTaskState.getTasks()
                .stream()
                .map(TaskEntity::getName)
                .filter(taskName -> taskName.equals(taskToMove.getName()))
                .findAny()
                .ifPresent(existingTask -> {
                    throw new BadRequestException(
                            String.format("Task state \"%s\" already contains  task name \"%s\"",
                                    newTaskState.getName(), taskToMove.getName()), HttpStatus.BAD_REQUEST);
                });

        serviceHelper.replaceOldTaskPosition(taskToMove);

        currentTaskState.getTasks().remove(taskToMove);

        Optional<TaskEntity> optionalLastTaskInNewState = newTaskState.getTasks()
                .stream()
                .filter(task -> task.getRightTask().isEmpty())
                .findAny();

        optionalLastTaskInNewState.ifPresent(lastTask -> {
            lastTask.setRightTask(taskToMove);
            taskToMove.setLeftTask(lastTask);
            taskRepository.saveAndFlush(lastTask);
        });

        taskToMove.setRightTask(null);
        taskToMove.setTaskState(newTaskState);

        newTaskState.getTasks().add(taskToMove);

        TaskEntity updatedTask = taskRepository.saveAndFlush(taskToMove);

        TaskHistoryEntity taskHistory = TaskHistoryEntity.builder()
                .task(updatedTask)
                .username(currentUsername)
                .changeType("EDIT")
                .fieldName("task state")
                .oldValue(taskToMove.getTaskState().getName())
                .newValue(updatedTask.getTaskState().getName())
                .build();

        taskHistoryRepository.save(taskHistory);

        taskStateRepository.save(currentTaskState);
        taskStateRepository.save(newTaskState);

        return taskDtoFactory.makeTaskDto(updatedTask);
    }

    @CacheEvict(value = "tasks", allEntries = true)
    @Transactional
    public TaskDto assignTaskToUser(Long taskId, String username) {
        log.info("Assigning task with ID: {} for user: {}", taskId, username);
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        TaskEntity task = serviceHelper.getTaskOrThrowException(taskId);
        TaskStateEntity taskState = task.getTaskState();
        ProjectEntity project = taskState.getProject();
        UserEntity user = serviceHelper.getUserOrThrowException(username);

        if(!project.getUsers().contains(user)) {
            throw new BadRequestException("Project doesn't contain user: " + username, HttpStatus.BAD_REQUEST);
        }

        String usernameBefore = task.getAssignedUser().getUsername();

        task.setAssignedUser(user);
        taskRepository.save(task);

        emailService.sendEmail(
                user.getEmail(),
                "You have been assigned a task",
                "You have been assigned to the task: " + task.getName()
        );

        TaskHistoryEntity taskHistory = TaskHistoryEntity.builder()
                .task(task)
                .username(currentUsername)
                .changeType("EDIT")
                .fieldName("assigned user")
                .oldValue(usernameBefore)
                .newValue(task.getAssignedUser().getUsername())
                .build();

        taskHistoryRepository.save(taskHistory);

        return taskDtoFactory.makeTaskDto(task);
    }
}
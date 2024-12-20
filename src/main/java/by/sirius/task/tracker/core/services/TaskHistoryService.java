package by.sirius.task.tracker.core.services;

import by.sirius.task.tracker.api.dto.TaskHistoryDto;
import by.sirius.task.tracker.core.factories.TaskHistoryDtoFactory;
import by.sirius.task.tracker.store.repositories.TaskHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class TaskHistoryService {

    private final TaskHistoryDtoFactory taskHistoryDtoFactory;
    private final TaskHistoryRepository taskHistoryRepository;

    public List<TaskHistoryDto> getTaskHistoryByTaskId(Long taskId) {
        return taskHistoryRepository.findByTaskId(taskId)
                .stream()
                .map(taskHistoryDtoFactory::makeTaskHistoryDto)
                .collect(Collectors.toList());
    }
}

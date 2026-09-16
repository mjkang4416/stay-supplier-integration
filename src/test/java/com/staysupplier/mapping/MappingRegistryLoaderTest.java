package com.staysupplier.mapping;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MappingRegistryLoaderTest {

	private final MappingRegistry registry = mock(MappingRegistry.class);

	private final TaskScheduler taskScheduler = mock(TaskScheduler.class);

	private final MappingRegistryLoader loader = new MappingRegistryLoader(this.registry, this.taskScheduler,
			new MappingReloadProperties("0 30 4 * * *", "Asia/Seoul", Duration.ofMinutes(5)));

	@Test
	void loadsAtStartupAndFailsStartupWhenDbIsUnavailable() {
		this.loader.afterSingletonsInstantiated();
		verify(this.registry).reload();

		willThrow(new IllegalStateException("db down")).given(this.registry).reload();
		assertThatThrownBy(this.loader::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void dailyReloadKeepsExistingSnapshotAndRetriesOnceAfterDelay() {
		willThrow(new IllegalStateException("db down")).given(this.registry).reload();
		given(this.registry.loadedAt()).willReturn(Instant.parse("2026-09-16T04:30:00Z"));

		this.loader.reloadDaily();

		ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
		verify(this.taskScheduler).schedule(any(Runnable.class), when.capture());
		assertThat(when.getValue()).isAfter(Instant.now().plus(Duration.ofMinutes(4)));
	}

	@Test
	void dailyReloadDoesNotScheduleRetryWhenItSucceeds() {
		this.loader.reloadDaily();

		verify(this.registry).reload();
		verify(this.taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
	}

}

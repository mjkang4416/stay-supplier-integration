package com.staysupplier.mapping;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import com.staysupplier.mapping.MappingSyncResult.SupplierSyncResult;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class MappingSyncRunnerTest {

	private final MappingSyncJob job = mock(MappingSyncJob.class);

	private final MappingSyncRunner runner = new MappingSyncRunner(this.job);

	@Test
	void exitCodeIsZeroWhenEverySupplierSucceeded() {
		given(this.job.run()).willReturn(new MappingSyncResult(List.of(
				new SupplierSyncResult(Supplier.A, true, null, 2, 0, 0, 2, 0, 0, false),
				new SupplierSyncResult(Supplier.B, true, null, 1, 0, 0, 1, 0, 0, false))));

		this.runner.run(new DefaultApplicationArguments());

		assertThat(this.runner.getExitCode()).isZero();
	}

	@Test
	void exitCodeIsOneWhenAnySupplierFailedSoTheJobIsRetried() {
		given(this.job.run()).willReturn(new MappingSyncResult(List.of(
				new SupplierSyncResult(Supplier.A, true, null, 2, 0, 0, 2, 0, 0, false),
				SupplierSyncResult.failure(Supplier.B, FailureReason.UNAVAILABLE))));

		this.runner.run(new DefaultApplicationArguments());

		assertThat(this.runner.getExitCode()).isEqualTo(1);
	}

}

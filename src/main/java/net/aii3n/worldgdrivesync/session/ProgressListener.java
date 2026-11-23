package net.aii3n.worldgdrivesync.session;

/** Told how far a long operation has got, from whatever thread does the work. */
@FunctionalInterface
public interface ProgressListener {
	ProgressListener NONE = (step, done, total) -> {
	};

	enum Step {
		/** Packing the world folder into a zip. {@code done}/{@code total} count files. */
		ZIPPING,
		/** Sending the zip to the cloud store. {@code done}/{@code total} count bytes. */
		UPLOADING
	}

	void onProgress(Step step, long done, long total);
}

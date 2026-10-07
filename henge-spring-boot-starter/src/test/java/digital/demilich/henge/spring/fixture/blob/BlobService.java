package digital.demilich.henge.spring.fixture.blob;

import digital.demilich.henge.core.HengeService;
import digital.demilich.henge.core.ImmutableBytes;

@HengeService(name = "blob-service")
public interface BlobService {

    record Sheet(String name, ImmutableBytes png) {
    }

    ImmutableBytes reverse(ImmutableBytes bytes);

    Sheet rename(Sheet sheet, String name);

    /** A body of {@code size} zero bytes. */
    ImmutableBytes zeros(int size);
}

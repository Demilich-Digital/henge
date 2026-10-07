package digital.demilich.henge.spring.fixture.blob;

import digital.demilich.henge.core.ImmutableBytes;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = BlobService.class, version = 1)
public class BlobServiceImpl implements BlobService {

    @Override
    public ImmutableBytes reverse(ImmutableBytes bytes) {
        byte[] reversed = bytes.toByteArray();
        for (int i = 0; i < reversed.length / 2; i++) {
            byte swap = reversed[i];
            reversed[i] = reversed[reversed.length - 1 - i];
            reversed[reversed.length - 1 - i] = swap;
        }
        return ImmutableBytes.copyOf(reversed);
    }

    @Override
    public Sheet rename(Sheet sheet, String name) {
        return new Sheet(name, sheet.png());
    }

    @Override
    public ImmutableBytes zeros(int size) {
        return ImmutableBytes.copyOf(new byte[size]);
    }
}

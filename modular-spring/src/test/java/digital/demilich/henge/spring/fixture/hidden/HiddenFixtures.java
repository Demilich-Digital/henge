package digital.demilich.henge.spring.fixture.hidden;

/** Exposes a package-private service interface/impl to tests in other packages. */
public final class HiddenFixtures {

    private HiddenFixtures() {
    }

    public static Class<?> interfaceType() {
        return HiddenService.class;
    }

    public static Object newImpl() {
        return new HiddenServiceImpl();
    }

    interface HiddenService {
        String hello();
    }

    static class HiddenServiceImpl implements HiddenService {
        @Override
        public String hello() {
            return "hello";
        }
    }
}

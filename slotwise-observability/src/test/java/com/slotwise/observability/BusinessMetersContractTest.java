package com.slotwise.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.slotwise.observability.BusinessMeters.EventConsumer;
import com.slotwise.observability.BusinessMeters.NotificationOutcome;
import com.slotwise.observability.BusinessMeters.ReservationOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// The Grafana dashboards and alert rules can only hold the meter names and tag values as text, so nothing else would notice a typo there
// (an alert on outcome="faild" simply never fires). This reads grafana/ and checks every slotwise_* series and every outcome/consumer value it
// mentions against what the code can actually emit.
class BusinessMetersContractTest {

    // Maven runs the tests with the module as working directory; grafana/ is a sibling of the modules.
    private static final Path GRAFANA = Path.of("..", "grafana");
    private static final Pattern SERIES = Pattern.compile("slotwise_[a-z_]+");
    // In the dashboards' JSON the quotes inside an expression are escaped (\"created\"), in the alert YAML they are not.
    private static final String TAG = "%s\\s*=~?\\s*\\\\?\"([a-z_|]+)\\\\?\"";

    @Test
    void tagValues_areThePrometheusLabelsTheDashboardsRely_on() {
        assertThat(Arrays.stream(ReservationOutcome.values()).map(ReservationOutcome::tag)).containsExactly("created", "conflict");
        assertThat(Arrays.stream(NotificationOutcome.values()).map(NotificationOutcome::tag)).containsExactly("sent", "retry", "failed");
        assertThat(Arrays.stream(EventConsumer.values()).map(EventConsumer::tag)).containsExactly("booking", "audit", "notification");
    }

    @Test
    void grafanaFiles_onlyUseSeriesAndTagValuesTheCodeEmits() throws IOException {
        final Set<String> series = Set.of(prometheusName(BusinessMeters.RESERVATIONS), prometheusName(BusinessMeters.NOTIFICATIONS),
                prometheusName(BusinessMeters.DUPLICATE_EVENTS));
        final Set<String> outcomes = new HashSet<>();
        Arrays.stream(ReservationOutcome.values()).forEach(o -> outcomes.add(o.tag()));
        Arrays.stream(NotificationOutcome.values()).forEach(o -> outcomes.add(o.tag()));
        final Set<String> consumers = new HashSet<>();
        Arrays.stream(EventConsumer.values()).forEach(c -> consumers.add(c.tag()));

        final List<Path> files = grafanaFiles();
        assertThat(files).as("grafana/ dashboards and alert rules found next to the modules").isNotEmpty();
        for (final Path file : files) {
            final String text = Files.readString(file);
            // slotwise_<name>_total, ignoring the suffixes Prometheus adds to counters ("_bucket" etc. do not apply to counters).
            SERIES.matcher(text).results().map(m -> m.group()).forEach(name ->
                    assertThat(series).as("%s mentions %s", file.getFileName(), name).contains(name));
            valuesOf("outcome", text).forEach(v -> assertThat(outcomes).as("%s: outcome=%s", file.getFileName(), v).contains(v));
            valuesOf("consumer", text).forEach(v -> assertThat(consumers).as("%s: consumer=%s", file.getFileName(), v).contains(v));
        }
    }

    // slotwise.reservations -> slotwise_reservations_total (OTLP -> Prometheus turns dots into underscores and adds _total to counters).
    private static String prometheusName(final String meter) {
        return meter.replace('.', '_') + "_total";
    }

    private static List<String> valuesOf(final String tag, final String text) {
        return Pattern.compile(String.format(TAG, tag)).matcher(text).results()
                .flatMap(m -> Arrays.stream(m.group(1).split("\\|"))).toList();
    }

    private static List<Path> grafanaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(GRAFANA)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".yaml")).toList();
        }
    }
}

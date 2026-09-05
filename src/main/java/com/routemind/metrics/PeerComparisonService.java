package com.routemind.metrics;

import com.routemind.metrics.model.Models.MetricWithContext;
import com.routemind.metrics.spi.Sql;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * The third reference point: PEER comparison.
 *
 * "OTA is 96.4%" gains meaning against the SLA and against last month — and gains more
 * against the rest of the estate: 5 business units, 17 offices, 23 vendors. Being 96.4%
 * when the best site runs 99% is a different conversation from being the best.
 */
@Service
public class PeerComparisonService {

    public record PeerRow(String member, double value, long sampleSize, int rank) {}

    public record PeerComparison(String metric,
                                 String dimension,      // businessUnit | office
                                 String subject,        // the one being judged, may be null
                                 Double subjectValue,
                                 Integer subjectRank,
                                 int peerCount,
                                 double best,
                                 double median,
                                 double worst,
                                 List<PeerRow> peers,
                                 String headline) {}

    private final MetricService metrics;
    private final NamedParameterJdbcTemplate jdbc;

    public PeerComparisonService(MetricService metrics, NamedParameterJdbcTemplate jdbc) {
        this.metrics = metrics;
        this.jdbc = jdbc;
    }

    /**
     * Compute the metric separately for every business unit and rank them.
     * Reuses the registered MetricDefinition, so this works for ANY metric — nothing
     * here is OTA-specific.
     */
    public PeerComparison acrossBusinessUnits(String metricId, LocalDate from, LocalDate to,
                                              String subject) {
        List<String> units = jdbc.getJdbcTemplate().queryForList(
                "SELECT DISTINCT business_unit FROM trips ORDER BY 1", String.class);

        boolean higherBetter = metrics.metric(metricId, from, to, null)
                .map(m -> "HIGHER_IS_BETTER".equals(m.direction()))
                .orElse(true);

        List<PeerRow> rows = units.stream()
                .map(bu -> metrics.metric(metricId, from, to, bu).orElse(null))
                .filter(m -> m != null && m.sampleSize() > 0)
                .sorted(higherBetter
                        ? Comparator.comparingDouble(MetricWithContext::value).reversed()
                        : Comparator.comparingDouble(MetricWithContext::value))
                .map(m -> new PeerRow(m.businessUnit(), m.value(), m.sampleSize(), 0))
                .toList();

        // assign ranks (1 = best)
        List<PeerRow> ranked = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PeerRow r = rows.get(i);
            ranked.add(new PeerRow(r.member(), r.value(), r.sampleSize(), i + 1));
        }

        if (ranked.isEmpty()) {
            return new PeerComparison(metricId, "businessUnit", subject, null, null,
                    0, 0, 0, 0, List.of(), "No peer data available for this period.");
        }

        double best = ranked.get(0).value();
        double worst = ranked.get(ranked.size() - 1).value();
        double median = median(ranked.stream().map(PeerRow::value).sorted().toList());

        Double subjectValue = null;
        Integer subjectRank = null;
        if (subject != null) {
            for (PeerRow r : ranked) {
                if (subject.equals(r.member())) {
                    subjectValue = r.value();
                    subjectRank = r.rank();
                }
            }
        }

        return new PeerComparison(metricId, "businessUnit", subject, subjectValue, subjectRank,
                ranked.size(), Sql.round1(best), Sql.round1(median), Sql.round1(worst),
                ranked, headline(metricId, subject, subjectValue, subjectRank,
                        ranked.size(), best, median, ranked.get(0).member()));
    }

    private String headline(String metric, String subject, Double value, Integer rank,
                            int n, double best, double median, String leader) {
        if (subject == null || value == null) {
            return String.format("Across %d business units, %s ranges from %.1f (best: %s) "
                    + "to a median of %.1f.", n, metric, best, leader, median);
        }
        String standing = rank == 1 ? "the best of" : rank == n ? "the weakest of"
                : "ranked " + rank + " of";
        return String.format("%s is %.1f — %s %d business units. The leader (%s) is at %.1f, "
                + "median %.1f.", subject, value, standing, n, leader, best, median);
    }

    private static double median(List<Double> sorted) {
        if (sorted.isEmpty()) return 0;
        int m = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(m) : (sorted.get(m - 1) + sorted.get(m)) / 2.0;
    }
}

package money.paytm.seatreservation.service;

import money.paytm.seatreservation.api.dto.*;
import money.paytm.seatreservation.domain.*;
import money.paytm.seatreservation.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ShowService {

    private final ShowRepository shows;
    private final SeatRepository seats;

    public ShowService(ShowRepository shows, SeatRepository seats) {
        this.shows = shows;
        this.seats = seats;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest req, int defaultPerUserLimit) {
        // De-duplicate seat labels while preserving order; reject empties handled
        // by validation. Duplicate labels in the request would violate the
        // (show_id, seat_label) unique constraint, so we collapse them.
        List<String> labels = new ArrayList<>(new LinkedHashSet<>(req.seats()));
        int perUserLimit = req.per_user_limit() != null ? req.per_user_limit() : defaultPerUserLimit;

        UUID showId = UUID.randomUUID();
        Show show = new Show(showId, req.name(), req.price_paise(), perUserLimit, labels.size());
        shows.save(show);

        List<Seat> seatRows = labels.stream()
                .map(label -> new Seat(UUID.randomUUID(), showId, label))
                .collect(Collectors.toList());
        seats.saveAll(seatRows);

        return getShow(showId);
    }

    @Transactional(readOnly = true)
    public ShowResponse getShow(UUID showId) {
        Show show = shows.findById(showId)
                .orElseThrow(() -> new NotFoundException("show not found: " + showId));

        List<Seat> seatRows = seats.findByShowIdOrderBySeatLabel(showId);
        long available = 0, held = 0, confirmed = 0;
        List<ShowResponse.SeatView> views = new ArrayList<>(seatRows.size());
        for (Seat s : seatRows) {
            views.add(new ShowResponse.SeatView(s.getSeatLabel(), s.getStatus().name().toLowerCase()));
            switch (s.getStatus()) {
                case AVAILABLE -> available++;
                case HELD -> held++;
                case CONFIRMED -> confirmed++;
            }
        }
        ShowResponse.Counts counts = new ShowResponse.Counts(
                available, held, confirmed, show.getTotalSeats());
        return new ShowResponse(show.getId(), show.getName(), show.getPricePaise(),
                show.getPerUserLimit(), show.getTotalSeats(), counts, views);
    }
}

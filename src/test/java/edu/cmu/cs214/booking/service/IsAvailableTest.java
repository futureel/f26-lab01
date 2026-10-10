package edu.cmu.cs214.booking.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
import edu.cmu.cs214.booking.repo.InMemoryBookingStore;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BookingService#isAvailable}. The agent's original implementation
 * only checked whether an existing booking's <em>start</em> fell inside the queried
 * interval, which misses bookings that start earlier and overlap the query from
 * the left. These tests pin the correct behavior.
 */
class IsAvailableTest {

    private final Room roomA = new Room("A", "Alpha", 10);
    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");

    private BookingService newService() {
        return new BookingService(new InMemoryBookingStore());
    }

    /**
     * E starts before Q and runs into Q: E=[570,630), Q=[600,660).
     * They overlap, so isAvailable must return false. The agent's patch returned
     * true because E.start=570 is not inside Q.
     */
    @Test
    void notAvailableWhenEarlierBookingRunsIntoTheRequest() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(570, 630));
        assertFalse(svc.isAvailable(roomA, new TimeInterval(600, 660)));
    }

    /**
     * E fully contains Q: E=[540,720), Q=[600,660. Must be unavailable.
     */
    @Test
    void notAvailableWhenExistingBookingContainsTheRequest() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(540, 720));
        assertFalse(svc.isAvailable(roomA, new TimeInterval(600, 660)));
    }

    /** An empty room is available. */
    @Test
    void availableOnEmptyRoom() {
        BookingService svc = newService();
        assertTrue(svc.isAvailable(roomA, new TimeInterval(600, 660)));
    }

    /** Back-to-back bookings do not overlap. */
    @Test
    void availableForBackToBackBooking() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        assertTrue(svc.isAvailable(roomA, new TimeInterval(660, 720)));
    }

    /**
     * Cross-check: isAvailable's answer must agree with what book() actually does.
     * If isAvailable says true, book() should confirm; if false, book() should waitlist.
     */
    @Test
    void isAvailableAgreesWithBook() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));

        // Same slot: not available, book() waitlists.
        assertFalse(svc.isAvailable(roomA, new TimeInterval(600, 660)));
        assertTrue(svc.book(roomA, bob, new TimeInterval(600, 660))
                instanceof BookingResult.Waitlisted);

        // Free slot: available, book() confirms.
        assertTrue(svc.isAvailable(roomA, new TimeInterval(660, 720)));
        assertTrue(svc.book(roomA, bob, new TimeInterval(660, 720))
                instanceof BookingResult.Confirmed);
    }
}

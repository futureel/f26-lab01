package edu.cmu.cs214.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.booking.domain.Booking;
import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
import edu.cmu.cs214.booking.repo.InMemoryBookingStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingServiceTest {

    private final Room roomA = new Room("A", "Alpha", 10);
    private final Room roomB = new Room("B", "Beta", 4);
    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");
    private final User carol = new User("u3", "Carol");
    private final User dave = new User("u4", "Dave");

    private BookingService newService() {
        return new BookingService(new InMemoryBookingStore());
    }

    @Test
    void bookConfirmsWhenRoomIsFree() {
        BookingService svc = newService();
        BookingResult r = svc.book(roomA, alice, new TimeInterval(600, 660));
        assertInstanceOf(BookingResult.Confirmed.class, r);
    }

    @Test
    void bookWaitlistsWhenSlotIsTaken() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        BookingResult r = svc.book(roomA, bob, new TimeInterval(630, 700));
        assertInstanceOf(BookingResult.Waitlisted.class, r);
    }

    @Test
    void backToBackBookingsAreConfirmed() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        BookingResult r = svc.book(roomA, bob, new TimeInterval(660, 720));
        assertInstanceOf(BookingResult.Confirmed.class, r);
    }

    @Test
    void sameSlotInDifferentRoomsAreBothConfirmed() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        BookingResult r = svc.book(roomB, bob, new TimeInterval(600, 660));
        assertInstanceOf(BookingResult.Confirmed.class, r);
    }

    @Test
    void listBookingsReturnsConfirmedBookings() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        svc.book(roomA, bob, new TimeInterval(660, 720));
        assertEquals(2, svc.listBookings(roomA).size());
    }

    /**
     * Regression test: bookingsForRoom must return only the bookings held for that
     * room, not all bookings across all rooms. The original bug returned every booking
     * regardless of room, causing bookings in other rooms to be treated as conflicts.
     */
    @Test
    void listBookingsIsScopedToOneRoom() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        svc.book(roomB, bob, new TimeInterval(600, 660));
        assertEquals(1, svc.listBookings(roomA).size());
        assertEquals(1, svc.listBookings(roomB).size());
    }

    // ---- cancelBooking: step 1 — removal and unknown-id handling ----

    @Test
    void cancelBookingRemovesTheBooking() {
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        BookingResult first = svc.book(roomA, alice, new TimeInterval(600, 660));
        String bookingId = ((BookingResult.Confirmed) first).booking().id();
        assertEquals(1, store.bookingsForRoom(roomA).size());

        svc.cancelBooking(bookingId);

        assertEquals(0, store.bookingsForRoom(roomA).size());
    }

    @Test
    void cancelUnknownIdDoesNothing() {
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        svc.book(roomA, alice, new TimeInterval(600, 660));
        svc.book(roomA, bob, new TimeInterval(630, 700)); // waitlisted

        svc.cancelBooking("does-not-exist");

        // Booking still there, waitlist untouched.
        assertEquals(1, store.bookingsForRoom(roomA).size());
        assertEquals(1, store.waitlistForRoom(roomA).size());
    }

    // ---- cancelBooking: step 2 — promotion ----

    @Test
    void cancellingPromotesTheWaitlistedUser() {
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        BookingResult first = svc.book(roomA, alice, new TimeInterval(600, 660));
        String aliceBookingId = ((BookingResult.Confirmed) first).booking().id();
        assertInstanceOf(BookingResult.Waitlisted.class,
                svc.book(roomA, bob, new TimeInterval(630, 700)));

        svc.cancelBooking(aliceBookingId);

        List<Booking> roomABookings = store.bookingsForRoom(roomA);
        assertEquals(1, roomABookings.size());
        assertEquals(bob.id(), roomABookings.get(0).user().id());
        assertEquals(new TimeInterval(630, 700), roomABookings.get(0).interval());
        assertTrue(store.waitlistForRoom(roomA).isEmpty());
    }

    // ---- cancelBooking: step 3 — FCFS, skip-on-conflict, room isolation ----

    @Test
    void promotesOnlyOneEvenWhenMultipleWaitersFit() {
        // S4: Alice holds [600,720); Bob [600,660) and Carol [660,700) both wait.
        // After Alice cancels, both slots are free, but only the earliest waiter gets promoted.
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        String aliceId = ((BookingResult.Confirmed) svc.book(roomA, alice, new TimeInterval(600, 720))).booking().id();
        assertInstanceOf(BookingResult.Waitlisted.class, svc.book(roomA, bob, new TimeInterval(600, 660)));
        assertInstanceOf(BookingResult.Waitlisted.class, svc.book(roomA, carol, new TimeInterval(660, 700)));

        svc.cancelBooking(aliceId);

        List<Booking> bookings = store.bookingsForRoom(roomA);
        assertEquals(1, bookings.size());
        assertEquals(bob.id(), bookings.get(0).user().id()); // earliest waiter promoted
        assertEquals(1, store.waitlistForRoom(roomA).size()); // Carol still waiting
    }

    @Test
    void skipsWaiterStillConflictingWithAnotherBooking() {
        // S5: Alice [600,660) and Dave [700,760) both confirmed.
        // Bob [630,730) waits first (still overlaps Dave), Carol [610,650) waits later (fits).
        // After Alice cancels, Bob must be skipped because of Dave, Carol promoted.
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        String aliceId = ((BookingResult.Confirmed) svc.book(roomA, alice, new TimeInterval(600, 660))).booking().id();
        svc.book(roomA, dave, new TimeInterval(700, 760));
        assertInstanceOf(BookingResult.Waitlisted.class, svc.book(roomA, bob, new TimeInterval(630, 730)));
        assertInstanceOf(BookingResult.Waitlisted.class, svc.book(roomA, carol, new TimeInterval(610, 650)));

        svc.cancelBooking(aliceId);

        List<Booking> bookings = store.bookingsForRoom(roomA);
        assertEquals(2, bookings.size()); // Dave + Carol promoted
        assertTrue(bookings.stream().anyMatch(b -> b.user().id().equals(dave.id())));
        assertTrue(bookings.stream().anyMatch(b -> b.user().id().equals(carol.id())));
        assertEquals(1, store.waitlistForRoom(roomA).size()); // Bob still waiting
        assertTrue(store.waitlistForRoom(roomA).stream().allMatch(w -> w.user().id().equals(bob.id())));
    }

    @Test
    void doesNotPromoteWaitersFromAnotherRoom() {
        // S6: Alice holds a booking in roomA; roomB is taken by Carol, so Bob waits in roomB.
        // Cancelling Alice must not promote Bob (who is waiting for roomB).
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        String aliceId = ((BookingResult.Confirmed) svc.book(roomA, alice, new TimeInterval(600, 660))).booking().id();
        svc.book(roomB, carol, new TimeInterval(600, 660)); // roomB is taken
        assertInstanceOf(BookingResult.Waitlisted.class, svc.book(roomB, bob, new TimeInterval(600, 660)));

        svc.cancelBooking(aliceId);

        assertTrue(store.bookingsForRoom(roomA).isEmpty());
        assertEquals(1, store.bookingsForRoom(roomB).size()); // Carol still holds roomB
        assertEquals(1, store.waitlistForRoom(roomB).size()); // Bob still on roomB waitlist
    }
}

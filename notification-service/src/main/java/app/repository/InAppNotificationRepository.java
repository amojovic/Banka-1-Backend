package app.repository;

import app.entities.InAppNotification;
import app.entities.RecipientType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data repository for {@link InAppNotification} rows.
 *
 * <p>Every access path is scoped by {@code recipientUserId} + {@code recipientType}
 * because the id space is not globally unique across clients and employees and
 * a caller must never observe another user's notifications.
 */
@Repository
public interface InAppNotificationRepository extends JpaRepository<InAppNotification, Long> {

    /**
     * Returns one page of the caller's notifications, newest first.
     *
     * @param recipientUserId owner id from the JWT {@code id} claim
     * @param recipientType   owner id space discriminator
     * @param pageable        page request (the caller-supplied sort is ignored
     *                         in favour of the explicit {@code createdAt DESC})
     * @return page of notifications scoped to the owner
     */
    Page<InAppNotification> findByRecipientUserIdAndRecipientTypeOrderByCreatedAtDesc(
            Long recipientUserId,
            RecipientType recipientType,
            Pageable pageable
    );

    /**
     * Counts the caller's unread notifications.
     *
     * @param recipientUserId owner id from the JWT {@code id} claim
     * @param recipientType   owner id space discriminator
     * @return number of unread notifications owned by the caller
     */
    long countByRecipientUserIdAndRecipientTypeAndReadFalse(
            Long recipientUserId,
            RecipientType recipientType
    );

    /**
     * Looks up a single notification by id, scoped to its owner so a caller can
     * never mark another user's notification read.
     *
     * @param id              notification primary key
     * @param recipientUserId owner id from the JWT {@code id} claim
     * @param recipientType   owner id space discriminator
     * @return the notification when it exists and belongs to the caller
     */
    Optional<InAppNotification> findByIdAndRecipientUserIdAndRecipientType(
            Long id,
            Long recipientUserId,
            RecipientType recipientType
    );

    /**
     * Bulk-marks every unread notification owned by the caller as read.
     *
     * @param recipientUserId owner id from the JWT {@code id} claim
     * @param recipientType   owner id space discriminator
     * @return number of rows updated
     */
    @Modifying
    @Query("update InAppNotification n set n.read = true "
            + "where n.recipientUserId = :recipientUserId "
            + "and n.recipientType = :recipientType and n.read = false")
    int markAllReadForRecipient(
            @Param("recipientUserId") Long recipientUserId,
            @Param("recipientType") RecipientType recipientType
    );
}

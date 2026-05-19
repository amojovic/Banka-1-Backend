package app.entities;

/**
 * Classifies the owner of an in-app notification.
 *
 * <p>The notification-service stores notifications for two distinct identity
 * spaces: bank clients and bank employees. The id space is not globally unique
 * across the two, so {@code recipientUserId} alone is ambiguous — every in-app
 * row is therefore scoped by both the id and this discriminator.
 */
public enum RecipientType {

    /** Notification belongs to a bank client. */
    CLIENT,

    /** Notification belongs to a bank employee. */
    EMPLOYEE
}

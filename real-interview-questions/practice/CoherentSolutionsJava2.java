package codinpad.service;

import java.time.Instant;

import org.springframework.transaction.annotation.Transactional;

import codinpad.model.Event;
import codinpad.model.Notification;
import codinpad.repository.*;
import codinpad.service.notification.NotificationSender;

public class EventService {

    private EventRepository eventRepository;
    private NotificationSender notificationSender;


    public void saveEvent(final Event newEvent) {
        System.out.println("[TRACE] Saving new event");

        saveEventAndSendNotification(newEvent);

        System.out.println("[TRACE] New event saved");
    }

    @Transactional
    public void saveEventAndSendNotification(final Event newEvent) {
        eventRepository.save(newEvent);

        try {
            //Send a notification to the external system
            notificationSender.sendNotification(new Notification(newEvent.getType()));//sending Kafka event
        } catch (Exception e) {
            throw new RuntimeException("Notication was not sent to the external system");
        }
    }
}
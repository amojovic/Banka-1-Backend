package main

import (
	"context"
	"fmt"
	"log"
	"log/slog"
	"net/http"
	"os"
	"time"

	"Banka1Back/notification-service-go/internal/config"
	"Banka1Back/notification-service-go/internal/httpapi"
	"Banka1Back/notification-service-go/internal/messaging"
	"Banka1Back/notification-service-go/internal/push"
	"Banka1Back/notification-service-go/internal/service"
	"Banka1Back/notification-service-go/internal/smtp"
	"Banka1Back/notification-service-go/internal/store"
	"Banka1Back/notification-service-go/internal/template"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	})))

	cfg, err := config.Load()
	if err != nil {
		log.Println("configuration load failed:", err)
		os.Exit(1)
	}

	db, err := config.NewDatabasePool(cfg.DB)
	if err != nil {
		log.Fatal("database connection failed:", err)
	}
	defer db.Close()
	log.Println("database connected")

	err = store.RunMigrations(context.Background(), db, "migrations")
	if err != nil {
		log.Fatal("database migrations failed:", err)
	} else {
		log.Println("database migrations applied")
	}

	deliveryStore := store.NewNotificationDeliveryStore(db)

	registry := template.NewDefaultTemplateRegistry(nil)
	renderer := template.NewRenderer(registry)
	smtpSender := smtp.NewSender(cfg.SMTP)

	schedulerInterval := time.Duration(cfg.Retry.SchedulerIntervalMs) * time.Millisecond
	scheduler := service.NewTickerRetryScheduler(deliveryStore, schedulerInterval, slog.Default())

	// Token registry is created unconditionally so the mobile app can always
	// register/deregister its device token (PUT/DELETE /notifications/fcm/token),
	// even when outbound FCM sending is not configured in this environment.
	fcmTokenStore := store.NewFcmTokenStore(db)

	var svcOpts []service.Option
	var fcmSender *push.FCMSender

	fcmConfig, fcmErr := push.SenderConfigFromEnv()
	if fcmErr != nil {
		log.Println("FCM not configured — push notifications disabled:", fcmErr)
	} else {
		sender, err := push.NewFCMSender(fcmConfig, slog.Default())
		if err != nil {
			log.Println("FCM initialization failed — push notifications disabled:", err)
		} else {
			fcmSender = sender
			svcOpts = append(svcOpts, service.WithPush(
				fcmTokenStore,
				fcmSender,
			))
			log.Println("FCM push notifications enabled")
		}
	}

	notificationService := service.NewNotificationService(
		deliveryStore,
		renderer,
		smtpSender,
		scheduler,
		cfg.Retry,
		slog.Default(),
		svcOpts...,
	)

	scheduler.SetService(notificationService)
	go scheduler.Start(context.Background())

	dispatcher := messaging.NewDispatcher(notificationService, slog.Default())
	consumer := messaging.NewConsumer(*cfg, dispatcher, slog.Default())

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	// FCM device-token registry endpoints (mobile login/logout). Ported from the
	// Java FcmTokenController so the PUT/DELETE /notifications/fcm/token contract
	// is identical across the Java→Go cut-over. POST /notifications/fcm/test is
	// enabled only when a push sender is configured (otherwise it returns 503).
	fcmHandler := httpapi.NewFcmHandler(fcmTokenStore, slog.Default())
	if fcmSender != nil {
		fcmHandler.WithTestPush(fcmTokenStore, fcmSender)
	}
	fcmHandler.Register(mux)

	port := fmt.Sprintf("%d", cfg.Server.HTTPPort)
	go func() {
		log.Println("notification-service HTTP started on port " + port)
		if err := http.ListenAndServe(":"+port, mux); err != nil {
			log.Println("HTTP server error:", err)
		}
	}()

	log.Println("starting AMQP consumer, queue:", cfg.Rabbit.Queue)
	if err := consumer.Run(context.Background()); err != nil {
		log.Fatal("consumer exited:", err)
	}
}

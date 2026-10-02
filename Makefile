BASE_URL ?= http://localhost:8080

# The deployed instance (Render free tier).
LIVE_URL ?= https://seat-reservation-yrkf.onrender.com

.PHONY: build test up down run burst burst-live logs

build:
	./mvnw -B -DskipTests clean package

test:
	./mvnw -B test

up:
	docker compose up --build -d

down:
	docker compose down -v

logs:
	docker compose logs -f app

run:
	./mvnw spring-boot:run

# Fire the on-sale stampede (hot-seat storm + stampede + idempotency + limit)
# against BASE_URL. Override: make burst BASE_URL=https://your-app.onrender.com
burst:
	./burst.sh $(BASE_URL)

# Same stampede, but against the deployed live URL.
burst-live:
	./burst.sh $(LIVE_URL)

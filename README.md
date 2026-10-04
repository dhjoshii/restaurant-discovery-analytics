# Zaika — Restaurant Discovery & Analytics System

**Advanced Big Data Analytics · Assignment**
**Dhairya Joshi** · Enrollment no. **25162123003** · Branch / Batch **BDA-75**

*Zaika* (ज़ायक़ा, "flavour") is a Scala 3 + MongoDB application for discovering and analysing **restaurants across India** — 8,652 restaurants in 43 cities with 1.19 million diner votes, imported from the Kaggle *Zomato Restaurants Data* into the MongoDB collection `sample_restaurants.india_restaurants`.

It ships as:

1. **A menu-driven CLI** — the primary interface the assignment asks for.
2. **A web application** with a Scala backend ([Cask](https://com-lihaoyi.github.io/cask/)) — a hand-drawn "spice market sketchbook" design: warm paper, turmeric → chilli → cardamom gradients, the Kalam handwriting font, sketchy [Rough.js](https://roughjs.com/) charts, doodles, sticky notes, draw-in scroll animations, light/dark themes and a mobile layout.

Both share the same Scala services and the official MongoDB Java driver.

**Live demo:** <https://zaika-restaurants.onrender.com> (Render free tier: the first visit after 15 idle minutes takes ~30–50 s to wake up).

![Home page](docs/screenshots/web-01-home-india.png)

---

## Contents

- [Requirement checklist](#requirement-checklist)
- [Project structure](#project-structure)
- [Setup](#setup)
- [Running the CLI](#running-the-cli)
- [Running the web app](#running-the-web-app)
- [Deploying to Render](#deploying-to-render)
- [Data model](#data-model)
- [Indexes](#indexes)
- [Aggregations](#aggregations)
- [REST API](#rest-api)
- [Screenshots](#screenshots)

---

## Requirement checklist

| Requirement | Where it is implemented |
|---|---|
| Variables, data types, conditions, functions | Throughout — e.g. [`IndiaValidation.scala`](src/restaurants/india/IndiaValidation.scala), [`IndiaModels.scala`](src/restaurants/india/IndiaModels.scala) (`RatingBand.textFor`, `PriceRange.fromCost`) |
| Collections: `map`, `filter`, `groupBy`, … | Page insights in [`IndiaMenus.scala`](src/restaurants/cli/IndiaMenus.scala) (`groupBy`, `flatMap`, `filter`, `minByOption`, `sortBy`); rating-band ordering and percentages in [`IndiaAnalytics.scala`](src/restaurants/india/IndiaAnalytics.scala); CSV handling in [`ZomatoImporter.scala`](src/restaurants/india/ZomatoImporter.scala) (`filter`, `foldLeft`, `grouped`) |
| `Option` and pattern matching | Defensive BSON decoding in [`IndiaRepository.scala`](src/restaurants/india/IndiaRepository.scala), `Option`-based patches, error mapping in [`WebServer.scala`](src/restaurants/web/WebServer.scala), every CLI menu |
| Exception / error handling | Sealed [`AppError`](src/restaurants/AppError.scala) hierarchy; `Try` → `Either` in [`IndiaService.scala`](src/restaurants/india/IndiaService.scala); duplicate-key retry on insert; unique-index fallback in [`IndexManager.scala`](src/restaurants/db/IndexManager.scala); UTF-8 → Windows-1252 fallback in the importer; JSON 400/404/503 errors in the API |
| Classes / case classes, objects, methods | `IndiaRestaurant`, `IndiaCriteria`, `IndiaDraft`, `IndiaPatch`, `Page`, `QueryPlan`; `enum IndiaSort`, `enum IndiaPreset`, `enum IndexStatus`; objects `RatingBand`, `IndiaCodec`, `PipelineDsl`, … |
| Encapsulation | [`MongoConnection`](src/restaurants/db/MongoConnection.scala) (private constructor & client), `PageRequest` (private constructor + validated factory), private caches and helpers in the service and repository |
| Trait / inheritance / composition | `trait IndiaRepository` implemented by `MongoIndiaRepository`; `trait MenuSupport` mixed into `IndiaMenus` (shared index menu & report printing); sealed `AppError` inheritance; the service *has a* repository and [`AppContext`](src/restaurants/AppContext.scala) composes the whole object graph |
| **Create** (≥ 2 records) | CLI 1 / `POST /api/restaurants` — the [CLI create screenshot](docs/screenshots/cli-06-create.png) inserts two restaurants |
| **Read** (view / search) | CLI 2 / `GET /api/restaurants`, `GET /api/restaurants/{id}` |
| **Update** (≥ 1) | CLI 3 / `PUT /api/restaurants/{id}` (`$set`); diner ratings via an atomic aggregation-pipeline update (`POST /api/restaurants/{id}/ratings`) |
| **Delete** (≥ 1) | CLI 4 / `DELETE /api/restaurants/{id}` (`findOneAndDelete`) |
| ≥ 3 search / filter operations | 8: name, city, cuisine, locality, minimum rating, budget (max cost for two), online delivery, table booking — combinable, with 5 sort orders |
| ≥ 2 indexes, ≥ 1 compound | 6 indexes: 5 compound (one multikey) + 1 unique — [Indexes](#indexes) |
| ≥ 3 aggregations | 7 + an overview — [Aggregations](#aggregations) |
| Menu-driven interface | [`CliApp.scala`](src/restaurants/cli/CliApp.scala) — exactly the 7 suggested menu items |
| Optional GUI / web with Scala backend | [`WebServer.scala`](src/restaurants/web/WebServer.scala) + [`resources/public`](resources/public) |

*(Apache Spark, the other optional extension, is not used.)*

> **Ratings** use Zomato's 1–5 scale — **higher is better**: Excellent ≥ 4.5, Very Good ≥ 4.0, Good ≥ 3.5, Average ≥ 2.5, Poor below; a rating of 0 means *Not rated*. **Costs** are in Indian Rupees for two people (0 = unknown).

---

## Project structure

```
.
├── project.scala                     # Scala CLI build: Scala 3.3 LTS, JDK 17, dependencies
├── src/restaurants
│   ├── AppContext.scala              # wires connection → repository → services
│   ├── AppError.scala                # sealed error hierarchy
│   ├── config/AppConfig.scala        # env vars / .env loading
│   ├── model/Common.scala            # Coordinates, PageRequest, Page, QueryPlan
│   ├── db/                           # MongoConnection, IndexManager, Explain
│   ├── analytics/Aggregation.scala   # Aggregation result + PipelineDsl (safe stage builder)
│   ├── india/                        # models, validation, repository (trait + Mongo impl),
│   │                                 #   indexes, analytics, service, ZomatoImporter
│   ├── cli/                          # CliApp (main menu), MenuSupport trait, IndiaMenus, Term
│   └── web/                          # WebServer (Cask routes), IndiaJson, JsonCodec
├── resources/public                  # web UI: index.html, assets/styles.css, assets/app.js
├── Dockerfile · render.yaml          # deployment
├── .env.example                      # configuration template (copy to .env)
└── docs/screenshots                  # evidence for the submission
```

---

## Setup

### Prerequisites

| Tool | Version | Install |
|---|---|---|
| JDK | 17 or newer | <https://adoptium.net> |
| Scala CLI | 1.x | <https://scala-cli.virtuslab.org/install> (Windows installer · macOS `brew install Virtuslab/scala-cli/scala-cli` · Linux `curl -sSLf https://scala-cli.virtuslab.org/get \| sh`) |
| MongoDB | Atlas (free M0 is fine) or any MongoDB 5.2+ | <https://www.mongodb.com/cloud/atlas> |

sbt is **not** required — Scala CLI reads every dependency from [`project.scala`](project.scala).

### 1. Configure the connection

```bash
git clone https://github.com/dhjoshii/restaurant-discovery-analytics.git
cd restaurant-discovery-analytics
cp .env.example .env        # Windows: copy .env.example .env
```

Edit `.env`:

```
MONGODB_URI=mongodb+srv://<user>:<password>@<cluster>.mongodb.net/?retryWrites=true&w=majority
MONGODB_DB=sample_restaurants
MONGODB_COLLECTION=india_restaurants
```

`.env` is git-ignored; real environment variables take precedence. In Atlas → **Security → Network Access**, allow your IP (or `0.0.0.0/0` for cloud hosting).

### 2. Import the restaurants

Download **Zomato Restaurants Data** from Kaggle (<https://www.kaggle.com/datasets/shrutimehta/zomato-restaurants-data>, file `zomato.csv`) and run the Scala importer:

```bash
scala-cli run . --main-class restaurants.india.ZomatoImporter -- path/to/zomato.csv
```

The importer parses the CSV (RFC 4180, with an encoding fallback), keeps the 8,652 rows with *Country Code 1* (India), turns cuisines into an array and Yes/No into booleans, derives the rating band, inserts in batches of 1,000 and creates the indexes. Add `--replace` to reload.

---

## Running the CLI

```bash
scala-cli run . --main-class restaurants.cli.CliApp
```

```
  ZAIKA  ·  Restaurant Discovery & Analytics
  ✔ Connected to sample_restaurants.india_restaurants on cluster0.xxxxx.mongodb.net
  ✔ 6 application indexes ready

  MAIN MENU ──────────────────────────────────────
   1  Add Restaurant
   2  Search / View Restaurants
   3  Update Restaurant
   4  Delete Restaurant
   5  Restaurant Analytics
   6  Index Information
   7  Exit
```

Optional flags (after `--`): `--no-color` (also honours `NO_COLOR`), `--ascii` (plain tables for old consoles), `--echo-input` (prints answers when piping a script, e.g. `… -- --echo-input < script.txt`).

Tips: type `?` at a city or cuisine prompt to list valid values; in result lists type `n`/`p` to page, a row number for details, `i` for in-memory page insights; in analytics type `p` to print the aggregation pipeline.

---

## Running the web app

```bash
scala-cli run . --main-class restaurants.web.WebServer
```

Open <http://localhost:8080> (set `PORT` to change it). The server creates the indexes on first connection, serves the UI from `resources/public`, and exposes the [REST API](#rest-api).

Highlights: live `explain()` plan chip on every search · detail drawer (rating, cost, services, map link) · add / edit / rate / delete modals · seven sketched aggregation charts, each with *Chart / Table / Pipeline* views and city / cuisine scope filters · index cards and an explain lab (planner vs forced collection scan) · count-ups, ripples, wiggles and draw-in doodles · `prefers-reduced-motion` support · every API value HTML-escaped.

### Build a single runnable jar

```bash
scala-cli --power package . --main-class restaurants.web.WebServer --assembly --preamble=false -o zaika-web.jar
java -jar zaika-web.jar
```

---

## Deploying to Render

The repository includes a [`Dockerfile`](Dockerfile) (Scala CLI builds an assembly jar; a JRE image runs it) and a [`render.yaml`](render.yaml) Blueprint.

1. In Atlas → **Security → Network Access**, add `0.0.0.0/0` (Render's free tier has no fixed IP).
2. On <https://dashboard.render.com>: **New → Blueprint** → connect this GitHub repository.
3. Paste your `MONGODB_URI` when asked and click **Apply**. The first build takes ~5–8 minutes.

Free services sleep after 15 idle minutes; the first request afterwards takes ~30–50 seconds.

---

## Data model

One document per restaurant in `sample_restaurants.india_restaurants`:

```json
{
  "restaurant_id": 18500653,
  "name": "Saffron Dhaba & Grill",
  "city": "New Delhi",
  "locality": "Connaught Place",
  "address": "N-12 Middle Circle",
  "cuisines": ["North Indian", "Mughlai"],
  "cost_for_two": 1500,
  "currency": "INR",
  "price_range": 3,
  "rating": 4.3,
  "rating_text": "Very Good",
  "votes": 2,
  "has_table_booking": false,
  "has_online_delivery": true,
  "coord": [77.2167, 28.6315]
}
```

---

## Indexes

Created idempotently at start-up by [`IndexManager`](src/restaurants/db/IndexManager.scala) from [`IndiaIndexes.scala`](src/restaurants/india/IndiaIndexes.scala) (and from CLI menu 6 → 2 or the **Create / verify indexes** button). Every index backs a concrete query; sorts include `restaurant_id` as a tie-breaker so paging is stable.

| Index | Type | Used by |
|---|---|---|
| `restaurant_id_1` | single, **unique** | view / update / rate / delete by id; next id |
| `name_1_restaurant_id_1` | **compound** | name search (case-insensitive regex) + A→Z paging |
| `city_1_rating_-1_votes_-1_restaurant_id_1` | **compound** | city filter already sorted by top rating |
| `cuisines_1_rating_-1_votes_-1_restaurant_id_1` | **compound, multikey** | cuisine filter (array membership) sorted by rating |
| `rating_-1_votes_-1_restaurant_id_1` | **compound** | minimum-rating filter and the default "Top rated" order |
| `cost_for_two_1_restaurant_id_1` | **compound** | budget filter and cost sorting |

Example from the explain lab — *city = New Delhi, top rated*: `IXSCAN → FETCH → LIMIT` reads **20** index keys and 20 documents; a forced collection scan (`COLLSCAN → SORT`) reads every document in the collection (8,653).

---

## Aggregations

All in [`IndiaAnalytics.scala`](src/restaurants/india/IndiaAnalytics.scala). Pipelines are built with `PipelineDsl.doc(...)` (never string interpolation), so filter values cannot inject operators. The CLI (`p`) and the web UI (*Pipeline* tab) show the exact pipeline that ran. "Rated" means `votes > 0` **and** `rating > 0`.

| # | Report | Key stages |
|---|---|---|
| 1 | Restaurants by city (+ mean rating & cost) | `$group` with conditional `$avg` → `$sort` → `$limit` |
| 2 | Most popular cuisines (optionally per city) | `$match` → **`$unwind` the cuisines array** → `$group` |
| 3 | Best-rated cities (≥ 20 rated restaurants) | `$match` rated → `$group $avg` → `$match` on sample size |
| 4 | Average cost for two by city | `$match cost > 0` → `$group $avg/$min/$max` |
| 5 | Rating-band distribution (optionally per city) | `$group` by band; ordering and percentages in Scala |
| 6 | Top-rated restaurants (≥ 100 votes, city / cuisine scope) | `$match` → `$sort` (index-backed) → `$limit` → `$project` |
| 7 | Online delivery & table booking share by city | `$group` with `$sum $cond` → `$divide` / `$multiply` |
| + | Overview | `$group` + `$reduce` / `$setUnion` to count distinct cuisines across arrays |

Diner ratings use an **aggregation-pipeline update** (`$set` with `$cond`, `$floor` for half-up rounding and `$switch` for the band), so the running average is recomputed atomically inside MongoDB — e.g. 4.5 then 4.0 gives 4.3 from 2 votes.

---

## REST API

| Method | Path | Description |
|---|---|---|
| GET | `/api/health` | server + database status |
| GET | `/api/meta` | cities, cuisines, rating bands, sort options, overview numbers |
| GET | `/api/restaurants?name=&city=&cuisine=&locality=&minRating=&maxCost=&online=1&table=1&sort=rating\|votes\|cost\|-cost\|name&page=&pageSize=&explain=1` | search (paged) |
| GET | `/api/restaurants/{restaurant_id}` | one restaurant |
| POST | `/api/restaurants` | create (`name, city, locality, address, cuisines, costForTwo, priceRange, onlineDelivery, tableBooking, latitude, longitude`) |
| PUT | `/api/restaurants/{restaurant_id}` | partial update (any of the fields above) |
| POST | `/api/restaurants/{restaurant_id}/ratings` | add a diner rating (`{"rating": 4.5}`) |
| DELETE | `/api/restaurants/{restaurant_id}` | delete |
| GET | `/api/analytics/{overview,cities,cuisines,city-ratings,city-costs,ratings,top-rated,services}` | aggregations (each response includes its pipeline) |
| GET · POST · GET | `/api/indexes` · `/api/indexes/ensure` · `/api/indexes/explain?preset=…` | list · create/verify · explain |

---

## Screenshots

All screenshots were captured from the running application against MongoDB Atlas; CLI screenshots are real terminal sessions rendered to images.

### CLI

| | |
|---|---|
| **Menu + index list** ![](docs/screenshots/cli-01-menu-indexes.png) | **Explain: index vs collection scan** ![](docs/screenshots/cli-02-explain.png) |
| **Create (2 records + validation errors)** ![](docs/screenshots/cli-06-create.png) | **Update + atomic ratings** ![](docs/screenshots/cli-07-update-rate.png) |
| **Delete (+ not-found handling)** ![](docs/screenshots/cli-08-delete.png) | **City search + page insights** ![](docs/screenshots/cli-03-search-city-insights.png) |
| **Advanced search** ![](docs/screenshots/cli-04-search-advanced.png) | **Rating, budget, view by id** ![](docs/screenshots/cli-05-search-rating-budget-view.png) |
| **Analytics: cities (with pipeline), cuisines** ![](docs/screenshots/cli-09-analytics-cities-cuisines.png) | **Analytics: ratings, costs, bands** ![](docs/screenshots/cli-10-analytics-ratings-costs-bands.png) |
| **Analytics: top rated, delivery, overview** ![](docs/screenshots/cli-11-analytics-top-services-overview.png) | |

### Web — CRUD

| | |
|---|---|
| Create form ![](docs/screenshots/web-05-create-form.png) | Created ![](docs/screenshots/web-06-created.png) |
| Update form ![](docs/screenshots/web-07-update-form.png) | Updated ![](docs/screenshots/web-08-updated.png) |
| Rate ![](docs/screenshots/web-09-rate.png) | Rated ![](docs/screenshots/web-10-rated.png) |
| Delete confirmation ![](docs/screenshots/web-11-delete-confirm.png) | Deleted ![](docs/screenshots/web-12-deleted.png) |
| Detail view ![](docs/screenshots/web-13-detail.png) | |

### Web — search & filters

| | |
|---|---|
| Name ![](docs/screenshots/web-02-search-name.png) | City + cuisine + rating + delivery ![](docs/screenshots/web-03-search-filters.png) |
| Budget, cheapest first ![](docs/screenshots/web-04-search-budget.png) | |

### Web — aggregations

| | |
|---|---|
| ![](docs/screenshots/web-14-analytics.png) | ![](docs/screenshots/web-15-analytics-ratings-costs.png) |
| ![](docs/screenshots/web-16-analytics-bands-delivery.png) | ![](docs/screenshots/web-17-analytics-top-rated.png) |
| Pipeline + table views ![](docs/screenshots/web-18-analytics-pipeline-table.png) | Scoped to New Delhi ![](docs/screenshots/web-19-analytics-scoped-delhi.png) |

### Web — indexes

| | |
|---|---|
| ![](docs/screenshots/web-20-indexes.png) | ![](docs/screenshots/web-21-explain.png) |

### Themes & mobile

| | |
|---|---|
| Dark theme ![](docs/screenshots/web-22-dark.png) | Mobile ![](docs/screenshots/web-23-mobile.png) |

---

<sub>Data: Kaggle "Zomato Restaurants Data" (India subset). Built with Scala 3, Cask, Rough.js and the MongoDB Java driver.</sub>

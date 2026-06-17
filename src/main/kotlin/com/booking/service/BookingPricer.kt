package com.booking.service

import com.booking.model.Booking
import com.booking.model.Quote
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class BookingPricer(
    private val service: BookingService,
    private val customers: CustomerService? = null
) {

    /**
     * Resolve the loyalty years to use for [bookingId].
     *
     * Returns the linked customer's [com.booking.model.Customer.loyaltyYears]
     * when a customer directory is wired in **and** the booking has a non-null
     * `customerId` **and** the directory holds that customer. Otherwise
     * returns [fallback] — typically the value the CLI prompted for. Callers
     * use this to skip a redundant prompt when the loyalty info is already
     * on file.
     */
    fun resolveLoyaltyYears(bookingId: String, fallback: Int): Int {
        val directory = customers ?: return fallback
        val booking = service.findBooking(bookingId) ?: return fallback
        val customerId = booking.customerId ?: return fallback
        return directory.find(customerId)?.loyaltyYears ?: fallback
    }


    fun calculateAndPrintAndMaybeSave(
        bookingId: String,
        customerType: String,
        partySize: Int,
        loyaltyYears: Int,
        couponCode: String?,
        prepay: Boolean,
        season: String,
        saveTo: String?
    ): Double {
        val b = service.findBooking(bookingId)
            ?: throw IllegalArgumentException("Unknown bookingId: $bookingId")

        val normalizedCustomerType = customerType.uppercase()
        if (normalizedCustomerType !in setOf("REGULAR", "VIP", "CORPORATE")) {
            throw IllegalArgumentException("Invalid customerType: $customerType. Must be REGULAR, VIP, or CORPORATE.")
        }

        val normalizedSeason = season.uppercase()
        if (normalizedSeason !in setOf("HIGH", "LOW", "MID")) {
            throw IllegalArgumentException("Invalid season: $season. Must be HIGH, LOW, or MID.")
        }

        var price = 0.0
        if (normalizedCustomerType == "REGULAR") {
            price = 100.0
            if (partySize > 0) {
                if (partySize == 1) {
                    price = price + 0.0
                } else if (partySize == 2) {
                    price = price + 50.0
                } else if (partySize == 3) {
                    price = price + 95.0
                } else if (partySize == 4) {
                    price = price + 140.0
                } else if (partySize >= 5 && partySize < 10) {
                    price = price + (partySize - 1) * 45.0
                    if (partySize >= 8) {
                        price = price - 20.0
                    }
                } else if (partySize >= 10) {
                    price = price + (partySize - 1) * 40.0
                    if (partySize > 15) {
                        price = price - 50.0
                        if (partySize > 20) {
                            price = price - 50.0
                            if (partySize > 30) {
                                price = price - 100.0
                            }
                        }
                    }
                }
            }
        } else if (normalizedCustomerType == "VIP") {
            price = 200.0
            if (partySize > 0) {
                if (partySize == 1) {
                    price = price + 0.0
                } else if (partySize == 2) {
                    price = price + 40.0
                } else if (partySize == 3) {
                    price = price + 75.0
                } else if (partySize == 4) {
                    price = price + 110.0
                } else if (partySize >= 5 && partySize < 10) {
                    price = price + (partySize - 1) * 35.0
                    if (partySize >= 8) {
                        price = price - 30.0
                    }
                } else if (partySize >= 10) {
                    price = price + (partySize - 1) * 30.0
                    if (partySize > 15) {
                        price = price - 60.0
                        if (partySize > 20) {
                            price = price - 60.0
                            if (partySize > 30) {
                                price = price - 120.0
                            }
                        }
                    }
                }
            }
        } else if (normalizedCustomerType == "CORPORATE") {
            price = 150.0
            if (partySize > 0) {
                if (partySize == 1) {
                    price = price + 0.0
                } else if (partySize == 2) {
                    price = price + 45.0
                } else if (partySize == 3) {
                    price = price + 85.0
                } else if (partySize == 4) {
                    price = price + 125.0
                } else if (partySize >= 5 && partySize < 10) {
                    price = price + (partySize - 1) * 40.0
                    if (partySize >= 8) {
                        price = price - 25.0
                    }
                } else if (partySize >= 10) {
                    price = price + (partySize - 1) * 35.0
                    if (partySize > 15) {
                        price = price - 55.0
                        if (partySize > 20) {
                            price = price - 55.0
                            if (partySize > 30) {
                                price = price - 110.0
                            }
                        }
                    }
                }
            }
        }
        // The customer-type guard above ensures we never reach a fall-through
        // case here, so no `else` branch is needed.

        val day = b.date.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            if (normalizedSeason == "HIGH") {
                price = price * 1.5
                if (normalizedCustomerType == "VIP") {
                    price = price * 0.95
                }
            } else if (normalizedSeason == "LOW") {
                price = price * 1.1
            } else {
                price = price * 1.25
            }
        } else if (day == DayOfWeek.FRIDAY) {
            if (normalizedSeason == "HIGH") {
                price = price * 1.3
            } else if (normalizedSeason == "LOW") {
                price = price * 1.05
            } else {
                price = price * 1.15
            }
        } else {
            if (normalizedSeason == "HIGH") {
                price = price * 1.2
            } else if (normalizedSeason == "LOW") {
                price = price * 0.9
            }
        }

        var loyaltyDiscount = 0.0
        if (loyaltyYears >= 1) {
            loyaltyDiscount = 0.02
            if (loyaltyYears >= 3) {
                loyaltyDiscount = 0.05
                if (loyaltyYears >= 5) {
                    loyaltyDiscount = 0.08
                    if (loyaltyYears >= 10) {
                        loyaltyDiscount = 0.12
                        if (loyaltyYears >= 20) {
                            loyaltyDiscount = 0.15
                        }
                    }
                }
            }
        }
        price = price - (price * loyaltyDiscount)

        // Coupon redemption is delegated to CouponService so the rules
        // (validity window, max uses, customer-type guard) live in one
        // place and can be edited via the CLI. Unknown codes and rejected
        // redemptions leave [price] unchanged but the result still tells
        // us what happened, so a follow-on caller could surface it.
        if (!couponCode.isNullOrEmpty()) {
            val redemption = coupons.tryApply(price, couponCode, normalizedCustomerType)
            price = redemption.newPrice
        }

        if (prepay) {
            if (price > 500.0) {
                price = price * 0.92
            } else if (price > 200.0) {
                price = price * 0.95
            } else {
                price = price * 0.97
            }
        }

        val today = LocalDate.now()
        val daysOut = java.time.temporal.ChronoUnit.DAYS.between(today, b.date)
        if (daysOut >= 0 && daysOut <= 2) {
            price = price * 1.1
        } else if (daysOut > 60) {
            price = price * 0.95
            if (daysOut > 120) {
                price = price * 0.97
                if (daysOut > 180) {
                    price = price * 0.98
                }
            }
        }

        if (price < 25.0) price = 25.0

        val rounded = (Math.round(price * 100.0)) / 100.0

        service.attachQuote(
            b.id,
            Quote(
                total = rounded,
                customerType = normalizedCustomerType,
                partySize = partySize,
                loyaltyYears = loyaltyYears,
                couponCode = couponCode,
                prepay = prepay,
                season = normalizedSeason
            )
        )

        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val out = StringBuilder()
        out.append("=== Booking Price Quote ===\n")
        out.append("Booking: ").append(b.id).append("\n")
        out.append("Customer: ").append(b.customerName).append("\n")
        out.append("Date: ").append(b.date.format(fmt)).append(" (").append(day).append(")\n")
        out.append("Type: ").append(customerType).append("\n")
        out.append("Party: ").append(partySize).append("\n")
        out.append("Loyalty years: ").append(loyaltyYears).append(" (-")
            .append((loyaltyDiscount * 100).toInt()).append("%)\n")
        out.append("Coupon: ").append(couponCode ?: "(none)").append("\n")
        out.append("Prepay: ").append(if (prepay) "yes" else "no").append("\n")
        out.append("Season: ").append(season).append("\n")
        out.append("---\n")
        out.append("TOTAL: $").append(rounded).append("\n")

        println(out.toString())

        if (saveTo != null && saveTo.isNotEmpty()) {
            try {
                File(saveTo).writeText(out.toString())
                println("Saved to $saveTo")
            } catch (e: Exception) {
                println("Save failed: " + e.message)
            }
        }

        return rounded
    }

    fun calculateAndPrintAndMaybeSaveForList(
        bookings: List<Booking>,
        customerType: String,
        partySize: Int,
        loyaltyYears: Int,
        couponCode: String?,
        prepay: Boolean,
        season: String,
        saveTo: String?
    ): Double {
        var total = 0.0
        for (b in bookings) {
            val one = calculateAndPrintAndMaybeSave(
                b.id, customerType, partySize, loyaltyYears, couponCode, prepay, season, null
            )
            if (one > 0) total = total + one
        }
        val rounded = Math.round(total * 100.0) / 100.0
        println("GRAND TOTAL: $%.2f".format(rounded))
        if (!saveTo.isNullOrEmpty()) {
            try {
                File(saveTo).writeText("GRAND TOTAL: $%.2f\n".format(rounded))
            } catch (e: Exception) {
                println("Save failed: ${e.message}")
            }
        }
        return rounded
    }
}

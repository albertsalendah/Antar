package com.richard_salendah.driverantar.ui.trip

import com.richard_salendah.driverantar.data.model.IncomingTripResponse

/**
 * Holds the [IncomingTripResponse] the driver tapped so [OfferPriceScreen]
 * can display the full trip summary without needing to serialise the entire
 * object through Navigation Compose route arguments.
 *
 * Lifecycle: set just before navigating to OfferPrice, cleared when
 * OfferPrice leaves the composition. Safe because only one trip can be
 * selected at a time.
 */
object TripSelectionHolder {
    var selectedTrip: IncomingTripResponse? = null
}
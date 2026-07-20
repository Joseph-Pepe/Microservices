package com.example.demo.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/* [@Valid]
    - request validation
    - validates the request by stopping bad data before it ever reaches your service logic.
    - prevent NullPointerException in service layer.
*/

// [@NotNull]: protects our model by telling spring that this object cannot be null or missing in the request.

// [Layer 2]: defines and stores the data.
public record VectorPair (
    @Valid // Tells Spring to also check inside the Vector3D object if needed
    @NotNull(message = "The first vector (v1) cannot be null or missing.") 
    Vector3D v1,

    @Valid
    @NotNull(message = "The second vector (v2) cannot be null or missing.") 
    Vector3D v2
) {
    // Java automatically creates the constructor, getters (v1() and v2()), equals(), and hashCode().
}
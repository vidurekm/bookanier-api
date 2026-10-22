package online.bookanear.api.dto;

/** Additional public-email requirement beyond Jakarta @Email: a dotted domain and a 2+ letter TLD. */
public final class EmailRules {
    private EmailRules() {}

    // The empty string is allowed here because optional emails may be blank.
    // Required email fields must additionally use @NotBlank.
    public static final String WITH_DOMAIN_SUFFIX_OR_EMPTY =
            "^$|^[^\\s@]+@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,}$";
}

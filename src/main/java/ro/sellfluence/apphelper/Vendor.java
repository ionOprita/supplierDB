package ro.sellfluence.apphelper;

public enum Vendor {
    judios,
    koppel,
    koppelfbe,
    sellfluence,
    sellfusion,
    zoopieconcept,
    zoopieinvest,
    zoopiesolutions;

    public static Vendor fromSheet(String name, boolean isFBE) {
        return switch (name) {
            case "Judios Concept SRL", "Judios RO FBE", "Judios Concept" -> judios;
            case "Koppel SRL", "Koppel" -> isFBE ? koppelfbe : koppel;
            case "Koppel FBE" -> koppelfbe;
            case "Sellfluence SRL", "Sellfluence FBE", "Sellfluence" -> sellfluence;
            case "Sellfusion SRL", "Sellflusion SRL", "SELLFUSION FBE" -> sellfusion;
            case "Zoopie Concept SRL", "Zoopie Concept FBE", "Zoopie Concept" -> zoopieconcept;
            case "Zoopie Invest SRL", "Zoopie Invest" -> zoopieinvest;
            case "Zoopie Solutions SRL", "Zoopie Solutions FBE", "Zoopie Solutions" -> zoopiesolutions;
            default -> throw new IllegalArgumentException("Unrecognized vendor '" + name + "' " + (isFBE ? "FBE" : ""));
        };
    }
}
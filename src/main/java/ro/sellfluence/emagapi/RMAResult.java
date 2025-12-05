package ro.sellfluence.emagapi;


import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record RMAResult(
        int is_full_fbe,
        int emag_id,
        Integer return_parent_id,
        String order_id,
        int type,
        int is_club,
        int is_fast,
        String customer_name,
        String customer_company,
        String customer_phone,
        String pickup_country,
        String pickup_suburb,
        String pickup_city,
        String pickup_address,
        String pickup_zipcode,
        Integer pickup_locality_id,
        int pickup_method,
        String customer_account_iban,
        String customer_account_bank,
        String customer_account_beneficiary,
        Integer replacement_product_emag_id,
        Integer replacement_product_id,
        String replacement_product_name,
        Integer replacement_product_quantity,
        String observations,
        int request_status,
        int return_type,
        int return_reason,
        LocalDateTime date,
        List<ReturnedProduct> products,
        ExtraInfo extra_info,
        String return_tax_value,
        String swap,
        String return_address_snapshot,
        List<AWB> awbs,
        List<StatusHistory> status_history,
        List<RequestHistory> request_history,
        @JsonDeserialize(using = LockerDeserializer.class)
        Locker locker,
        Integer return_address_id,
        String country,
        String address_type,
        Integer request_status_reason,
        String currency
) {
    public RMAResult {
        // Normalize Lists
        if (products == null) {
            products = new ArrayList<>();
        }
        if (awbs == null) {
            awbs = new ArrayList<>();
        }
        if (status_history == null) {
            status_history = new ArrayList<>();
        }
        if (request_history == null) {
            request_history = new ArrayList<>();
        }
    }

    public String statusAsString() {
        return switch (request_status) {
            case 1 -> "Incomplete";
            case 2 -> "New";
            case 3 -> "Approved";
            case 4 -> "Refused";
            case 5 -> "Canceled";
            case 6 -> "Received";
            case 7 -> "Finalizes";
            default -> throw new IllegalStateException("Unexpected value: " + request_status);
        };
    }


    public static final Map<Integer, String> returnReason = new HashMap<>();

    static {
        String nuCorespunde = "Produsul nu corespunde descrierii din site";
        String amPrimitAltProdus = "Am primit alt produs decât cel comandat";
        String altMotiv = "Alt motiv";
        returnReason.put(30, "Vreau să returnez un produs sigilat");
        returnReason.put(31, "Vreau să returnez un produs functional");
        returnReason.put(32, "Vreau să returnez un produs nefunctional");
        returnReason.put(34, "Mărimea nu corespunde");
        returnReason.put(35, "Produsul nu corespunde așteptărilor");
        returnReason.put(36, nuCorespunde);
        returnReason.put(37, "Produsul primit prezintă un defect sau este incomplet");
        returnReason.put(38, "Am comandat mai mult de o mărime");
        returnReason.put(39, amPrimitAltProdus);
        returnReason.put(40, "Am primit coletul deteriorat");
        returnReason.put(42, "Am găsit produsul la un preț mai bun");
        returnReason.put(43, amPrimitAltProdus);
        returnReason.put(44, "Am comandat produsul greșit");
        returnReason.put(45, altMotiv);
        returnReason.put(46, "Nu sunt mulțumit de produs");
        returnReason.put(47, "Produs incomplet sau cu accesorii lipsă");
        returnReason.put(48, nuCorespunde);
        returnReason.put(49, "Am găsit produsul la un preț mai bun");
        returnReason.put(50, amPrimitAltProdus);
        returnReason.put(51, "Am comandat produsul greșit");
        returnReason.put(52, altMotiv);
        returnReason.put(53, "Produsul este lovit/spart");
        returnReason.put(54, "Produsul este defect");
        returnReason.put(55, "Am comandat produsul greșit");
        returnReason.put(57, "Am primit mărimea comandata, dar produsul îmi este mic");
        returnReason.put(58, "Am primit mărimea comandata, dar produsul îmi este mare");
        returnReason.put(59, "Am primit o alta mărime decât cea comandata");
        returnReason.put(60, "Nu îmi place materialul");
        returnReason.put(61, "Nu îmi place culoarea");
        returnReason.put(62, "Nu îmi place croiala");
        returnReason.put(63, "Nu îmi place cum îmi vine");
        returnReason.put(64, "Nu sunt comozi");
        returnReason.put(65, "Nu îmi place dimensiunea");
        returnReason.put(66, "Nu îmi place cum este compartimentat");
        returnReason.put(67, altMotiv);
        returnReason.put(68, "Materialul este diferit");
        returnReason.put(69, "Culoarea este diferita");
        returnReason.put(70, "Dimensiunile sunt diferite fata de specificatiile din site");
        returnReason.put(71, "Căptușeala este diferită");
        returnReason.put(72, "Bareta are o alta dimensiune");
        returnReason.put(73, altMotiv);
        returnReason.put(74, "Materialul este patat");
        returnReason.put(75, "Materialul este descușat");
        returnReason.put(76, "Materialul este rupt");
        returnReason.put(77, "Materialul este zgâriat");
        returnReason.put(78, "Inchizatoarea este defectă");
        returnReason.put(79, "Cadranul este zgâriat");
        returnReason.put(80, "Cureaua este zgâriată");
        returnReason.put(81, "Produsul prezintă urme de oxidare");
        returnReason.put(82, "Remontorul este rupt");
        returnReason.put(83, "Lipsesc nasturi");
        returnReason.put(84, "Talpa este dezlipită");
        returnReason.put(85, "Fermoarul este defect");
        returnReason.put(86, "Lipsește un accesoriu");
        returnReason.put(87, "Catarama este defectă");
        returnReason.put(88, "Produsul prezintă urme de exfoliere");
        returnReason.put(89, "Produs incomplet sau cu accesorii lipsă");
        returnReason.put(90, "Produsul este lovit/spart");
        returnReason.put(91, "Produsul nu funcționează");
        returnReason.put(92, altMotiv);
        returnReason.put(93, "Am vrut să compar mărimile");
        returnReason.put(94, "Am comandat din greșeală");
        returnReason.put(95, altMotiv);
        returnReason.put(96, "Produsul este intact");
        returnReason.put(97, "Produsul a fost afectat");
        returnReason.put(98, "Tot la eMAG");
        returnReason.put(99, "La un alt magazin");
        returnReason.put(100, "Bateria se descărcă prea repede");
        returnReason.put(101, "Calitatea camerei foto este sub așteptări");
        returnReason.put(102, "Diagonala ecranului este mai mica decât ma asteptam");
        returnReason.put(103, "Calitatea sunetului este sub așteptările mele");
        returnReason.put(104, "Conexiunea la internet wireless este instabilă");
        returnReason.put(105, "Calitatea imaginii este sub așteptările mele");
        returnReason.put(106, "Nu îmi place platforma SmartTV/meniul TV-ului");
        returnReason.put(111, "Produsul nu corespunde așteptărilor mele");
        returnReason.put(112, "Lipsesc căștile");
        returnReason.put(113, "Lipsește încărcătorul");
        returnReason.put(114, "Lipsește cablul");
        returnReason.put(115, "Lipsește telecomanda");
        returnReason.put(116, "Lipsește piciorul (standul) televizorului");
        returnReason.put(117, "Lipsesc șuruburi");
        returnReason.put(118, "Lipsesc accesorii ale produsului");
        returnReason.put(120, "Tot la eMAG");
        returnReason.put(121, "La un alt magazin");
        returnReason.put(122, "Ambalajul este intact");
        returnReason.put(123, "Ambalajul este deteriorat");
        returnReason.put(124, "Nu porneste");
        returnReason.put(125, "Pixeli morti");
        returnReason.put(126, "Produsul are probleme de afișaj");
        returnReason.put(127, altMotiv);
        returnReason.put(128, "Am primit coletul deteriorat, dar cutia ceasului este intacta");
        returnReason.put(129, "Am primit coletul deteriorat si cutia ceasului este deteriorata");
        returnReason.put(130, "Am primit coletul in stare buna");
        returnReason.put(131, "Am primit coletul deteriorat, dar cutia ceasului este intacta");
        returnReason.put(132, "Am primit coletul deteriorat si cutia ceasului este deteriorata");
        returnReason.put(133, "Am primit coletul in stare buna");
        returnReason.put(134, "Produs acordat cadou");
        returnReason.put(135, "Nespecificat");
        returnReason.put(136, "Culoarea chiuvetei difera de cea a bateriei");
        returnReason.put(137, "Chiuveta nu este perforata pentru instalarea bateriei");
        returnReason.put(138, "Chiuveta nu se potrivește cu mobilierul");
        returnReason.put(139, "Chiuveta prezintă urme de uzura sau zgarieturi");
        returnReason.put(140, "Culoarea bateriei difera de cea a chiuvetei");
        returnReason.put(141, "Bateria prezintă urme de uzura sau zgarieturi");
        returnReason.put(142, "Produsul nu se potrivește cu mobilierul");
        returnReason.put(143, "Produsul prezintă urme de uzura sau zgarieturi");
        returnReason.put(144, "Lipsește bateria");
        returnReason.put(145, "Lipsesc sifonul si preaplinul");
        returnReason.put(146, "Lipsesc racorduri");
        returnReason.put(147, "Lipsesc usi");
        returnReason.put(148, "Lipsesc manere");
        returnReason.put(149, "Lipsesc role");
        returnReason.put(150, "Culoarea este diferita");
        returnReason.put(151, "Produsul are alte dimensiuni");
        returnReason.put(152, altMotiv);
        returnReason.put(153, "Lungimea pipei este diferita");
        returnReason.put(154, "Inaltimea bateriei este diferita");
        returnReason.put(155, "Diametrul este diferit");
        returnReason.put(157, "Dimensiunea nu este potrivita");
        returnReason.put(158, "Produsul nu este confortabil");
        returnReason.put(159, "Nu îmi place culoarea");
        returnReason.put(160, "Produsul este dificil de asamblat");
        returnReason.put(161, "Calitatea materialului este una slaba");
        returnReason.put(162, "Lipsesc roti");
        returnReason.put(163, "Lipsesc picioare");
        returnReason.put(166, "Pistonul este defect");
        returnReason.put(167, "Produsul este deteriorat");
        returnReason.put(168, "Materialul este patat");
        returnReason.put(169, "Materialul este rupt");
        returnReason.put(170, "Produsul prezintă urme de uzura sau zgârieturi");
        returnReason.put(171, "Altceva");
        returnReason.put(172, "Am primit un produs cu alte specificatii");
        returnReason.put(173, "Produsul este prea zgomotos");
        returnReason.put(174, "Nu sunt mulțumit de calitatea materialelor");
        returnReason.put(175, "Nu sunt mulțumit cum funcționează produsul");
        returnReason.put(176, "Diagonala ecranului este mai mare decât ma asteptam");
        returnReason.put(177, "Nu sunt mulțumit de greutatea produsului");
        returnReason.put(178, "Nu sunt mulțumim de calitatea produsului");
        returnReason.put(179, "Produsul se încălzește foarte tare");
        returnReason.put(180, "Produsul prezintă unul sau mai mulți pixeli morti");
        returnReason.put(181, "Nu funcționează tastatura/touchpad-ul");
        returnReason.put(182, "Produsul are display-ul spart");
        returnReason.put(183, "Ambalajul este intact");
        returnReason.put(184, "Ambalajul este deteriorat");
        returnReason.put(185, altMotiv);
        returnReason.put(186, "Produsul este lovit/spart");
        returnReason.put(187, "Ambalajul este intact");
        returnReason.put(188, "Ambalajul este deteriorat");
        returnReason.put(189, "Lipsesc șuruburile pentru montajul suportului inclus");
        returnReason.put(190, "Produsul prezintă unul sau mai mulți pixeli morti");
        returnReason.put(191, "Lipsește acumulatorul");
        returnReason.put(192, "Lipsesc electrozi");
        returnReason.put(193, "Lipsește masca");
        returnReason.put(194, "Lipsește sarma de sudura");
        returnReason.put(195, "Lipsesc duze");
        returnReason.put(196, "Lipsește rezervorul pentru vopsea");
        returnReason.put(197, "Produsul este zgâriat");
        returnReason.put(198, "Produsul este spart");
        returnReason.put(199, "Accesoriile sunt deteriorate");
        returnReason.put(200, "Calitatea produsului este sub așteptările mele");
        returnReason.put(201, "Calitatea accesoriilor este sub așteptările mele");
        returnReason.put(202, "Fotografia din site difera fata de produsul primit");
        returnReason.put(203, "Specificatiile prezentate in site diferta fata de produsul primit");
        returnReason.put(204, "Produsul nu se alimentează");
        returnReason.put(205, "Produsul se supraîncălzește");
        returnReason.put(206, "Lipsește sacul");
        returnReason.put(207, "Lipsește lanțul");
        returnReason.put(208, "Lipsește sina");
        returnReason.put(209, "Lipsește pistolul");
        returnReason.put(210, "Nu sunt mulțumit de viteza cu care televizorul raspunde la comenzile prin telecomanda");
        returnReason.put(211, "Nu sunt mulțumit de finisajul carcasei");
        returnReason.put(212, "Lipsește obiectivul produsului");
        returnReason.put(213, "Lipsește geanta/husa produsului");
        returnReason.put(214, "Software-ul produsului este greoi");
        returnReason.put(215, "Nu ma pot obișnui cu meniul produsului");
        returnReason.put(216, "Autonomia acumulatorului este sub așteptările mele");
        returnReason.put(217, "Focalizarea a produsului este sub așteptările mele");
        returnReason.put(218, "Calitatea pozelor este sub așteptările mele");
        returnReason.put(219, "Calitatea clipurilor video este sub așteptările mele");
        returnReason.put(220, "Nu sunt mulțumit de calitatea materialelor folosite la constructia produsului");
        returnReason.put(221, "Sunt diferente intre descrierea de pe site si produsul primit");
        returnReason.put(222, "Ma așteptam la o data de fabricatie mai recenta");
    }

    public String returnReasonAsString() {
        String s = returnReason.get(return_reason);
        if (s==null) throw new IllegalStateException("Unexpected return reason: " + return_reason);
        return s;
    }

    public String requestStatusReasonAsString(int reasonCode) {
        String s = returnReason.get(request_status_reason);
        if (s==null) throw new IllegalStateException("Unexpected request status reason: " + request_status_reason);
        return s;
    }
}
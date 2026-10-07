package dumb_phone.radio;

import android.content.Context;

/**
 * The colour themes, shared by all dumb_phone apps. The home app stores the chosen one and serves its
 * name at content://dumb_phone.theme/current; every app reads it when it opens (load) and redraws
 * itself if it changed (changed). Columns: background, cell, highlight, text, dim, rule, accent.
 */
final class Theme {
    static final String[] NAMES = {"phosphor", "amber", "nord", "gruvbox", "dracula", "catppuccin", "solarized", "tokyo night", "mono", "paper", "everforest", "rose pine", "kanagawa", "monokai", "one dark", "ayu", "crimson", "latte", "jake", "adventure time", "atom one light", "ayu light", "ayu mirage", "blue dolphin", "borland", "carbonfox", "catppuccin frappe", "catppuccin macchiato", "cobalt2", "coffee theme", "cyberpunk", "django smooth", "doom one", "embark", "everforest light med", "evergreen", "fairyfloss", "flexoki dark", "flexoki light", "fun forrest", "github dark dimmed", "github light", "github light default", "grass", "gruvbox light", "gruvbox material dark", "horizon", "hot dog stand", "iceberg dark", "iceberg light", "jackie brown", "kanagawa dragon", "kanagawa lotus", "laser", "man page", "material ocean", "melange dark", "melange light", "miami heat", "moonfly", "mustard", "night owl", "nightfox", "nord light", "novel", "ocean", "oceanic next", "oxocarbon", "paraiso dark", "poimandres", "powershell", "purple rain", "red alert", "red sands", "rose pine moon", "rose pine dawn", "sakura", "seafoam pastel", "selenized dark", "shades of purple", "snazzy", "solarized light", "sonokai", "spacegray", "squirrelsong dark", "synthwave", "tokyonight day", "tokyonight moon", "tomorrow night blue", "ubuntu", "unikitty", "velvet court"};
    static final int[][] P = {
            {0xFF040605, 0xFF0B1008, 0xFF16240E, 0xFF95EE62, 0xFF446B2D, 0xFF294A16, 0xFFFFB347},
            {0xFF080502, 0xFF140E05, 0xFF2A1D0A, 0xFFFFB347, 0xFF7A5524, 0xFF4A3416, 0xFFFFE3A3},
            {0xFF2E3440, 0xFF3B4252, 0xFF434C5E, 0xFF88C0D0, 0xFF616E88, 0xFF4C566A, 0xFFEBCB8B},
            {0xFF1D2021, 0xFF282828, 0xFF3C3836, 0xFFB8BB26, 0xFF7C6F64, 0xFF504945, 0xFFFE8019},
            {0xFF21222C, 0xFF282A36, 0xFF44475A, 0xFFBD93F9, 0xFF6272A4, 0xFF44475A, 0xFFFF79C6},
            {0xFF1E1E2E, 0xFF262637, 0xFF45475A, 0xFF89B4FA, 0xFF6C7086, 0xFF45475A, 0xFFF9E2AF},
            {0xFF002B36, 0xFF073642, 0xFF0E4B5A, 0xFF2AA198, 0xFF586E75, 0xFF0E4B5A, 0xFFB58900},
            {0xFF1A1B26, 0xFF24283B, 0xFF2F3549, 0xFF7AA2F7, 0xFF565F89, 0xFF414868, 0xFFE0AF68},
            {0xFF000000, 0xFF0D0D0D, 0xFF222222, 0xFFE6E6E6, 0xFF6E6E6E, 0xFF333333, 0xFFFFFFFF},
            {0xFFE9E6DE, 0xFFDDD9CF, 0xFFC9C3B5, 0xFF1E1E1E, 0xFF6B675E, 0xFFB3AD9F, 0xFFA0521C},
            {0xFF2D353B, 0xFF343F44, 0xFF475258, 0xFFA7C080, 0xFF859289, 0xFF4F585E, 0xFFDBBC7F},
            {0xFF191724, 0xFF1F1D2E, 0xFF26233A, 0xFFEBBCBA, 0xFF6E6A86, 0xFF403D52, 0xFFF6C177},
            {0xFF1F1F28, 0xFF2A2A37, 0xFF363646, 0xFFDCD7BA, 0xFF727169, 0xFF54546D, 0xFFFF9E3B},
            {0xFF272822, 0xFF2E2E2A, 0xFF3E3D32, 0xFFA6E22E, 0xFF75715E, 0xFF49483E, 0xFFF92672},
            {0xFF282C34, 0xFF2C313A, 0xFF3E4451, 0xFF61AFEF, 0xFF5C6370, 0xFF3E4451, 0xFFE5C07B},
            {0xFF0B0E14, 0xFF11151C, 0xFF1C212B, 0xFF39BAE6, 0xFF565B66, 0xFF242936, 0xFFFFB454},
            {0xFF070202, 0xFF140606, 0xFF2A0C0C, 0xFFFF5555, 0xFF7A2A2A, 0xFF4A1616, 0xFFFFB3B3},
            {0xFFEFF1F5, 0xFFE6E9EF, 0xFFCCD0DA, 0xFF4C4F69, 0xFF8C8FA1, 0xFFBCC0CC, 0xFF1E66F5},
            {0xFF030A75, 0xFF0B1280, 0xFF1A2290, 0xFFEDE8D0, 0xFF8E8DA8, 0xFF2A3196, 0xFFE2CF45},
            {0xFF1F1D45, 0xFF2C284C, 0xFF3D3856, 0xFFF8DCC0, 0xFF8C7C82, 0xFF534B63, 0xFFC8FAF4},
            {0xFFF9F9F9, 0xFFEDEDED, 0xFFDCDCDD, 0xFF2A2C33, 0xFF929296, 0xFFC7C8C9, 0xFFDE3E35},
            {0xFFF8F9FA, 0xFFEFF0F1, 0xFFE2E4E5, 0xFF5C6166, 0xFFAAADB0, 0xFFD3D5D6, 0xFFA37ACC},
            {0xFF1F2430, 0xFF292E39, 0xFF373B44, 0xFFCCCAC2, 0xFF767779, 0xFF494C53, 0xFFFFFFFF},
            {0xFF006984, 0xFF0C718B, 0xFF1C7C95, 0xFFC5F2FF, 0xFF62AEC2, 0xFF2F8AA2, 0xFFFFE585},
            {0xFF0000A4, 0xFF0F0F9F, 0xFF242498, 0xFFFFFF4E, 0xFF808079, 0xFF3D3D8F, 0xFFFF9CFE},
            {0xFF161616, 0xFF232324, 0xFF353536, 0xFFF2F4F8, 0xFF848587, 0xFF4B4B4C, 0xFF2DC7C4},
            {0xFF303446, 0xFF393D50, 0xFF454A5E, 0xFFC6D0F5, 0xFF7B829E, 0xFF545970, 0xFFECD7AE},
            {0xFF24273A, 0xFF2E3145, 0xFF3B3F54, 0xFFCAD3F5, 0xFF777D98, 0xFF4C5067, 0xFFF4E3C1},
            {0xFF132738, 0xFF213444, 0xFF344554, 0xFFFFFFFF, 0xFF89939C, 0xFF4C5B68, 0xFFEDC809},
            {0xFFF5DEB3, 0xFFE6D1A8, 0xFFD3BF9A, 0xFF000000, 0xFF7A6F5A, 0xFFBAA988, 0xFFCA30C7},
            {0xFF332A57, 0xFF3E3560, 0xFF4C446B, 0xFFE5E5E5, 0xFF8C889E, 0xFF5E5779, 0xFF1BCCFD},
            {0xFF245032, 0xFF315A3E, 0xFF42684E, 0xFFF8F8F8, 0xFF8EA495, 0xFF577862, 0xFFFFE862},
            {0xFF282C34, 0xFF31353D, 0xFF3D414A, 0xFFBBC2CF, 0xFF727782, 0xFF4B5059, 0xFFECBE7B},
            {0xFF1E1C31, 0xFF2A2A3D, 0xFF3B3C4E, 0xFFEEFFFF, 0xFF868E98, 0xFF505262, 0xFFFFB378},
            {0xFFEFEBD4, 0xFFE6E3CE, 0xFFDAD9C6, 0xFF5C6A72, 0xFFA6AAA3, 0xFFCCCCBC, 0xFF7A8478},
            {0xFF032B1B, 0xFF113728, 0xFF234839, 0xFFE9F7EF, 0xFF769185, 0xFF3A5C4E, 0xFFF6DF90},
            {0xFF5A5475, 0xFF635E7C, 0xFF706B86, 0xFFF8F8F2, 0xFFA9A6B4, 0xFF807B93, 0xFFFFB8D1},
            {0xFF100F0F, 0xFF1B1A1A, 0xFF2B2A28, 0xFFCECDC3, 0xFF6F6E69, 0xFF3E3D3A, 0xFFAD8301},
            {0xFFFFFCF0, 0xFFF1EEE2, 0xFFDEDBD0, 0xFF100F0F, 0xFF888680, 0xFFC6C3BA, 0xFFAD8301},
            {0xFF251200, 0xFF301C06, 0xFF3F2A0E, 0xFFDEC165, 0xFF826A32, 0xFF513C18, 0xFFD26349},
            {0xFF22272E, 0xFF2A3037, 0xFF353C43, 0xFFADBAC7, 0xFF68707A, 0xFF434A53, 0xFFC69026},
            {0xFFFFFFFF, 0xFFF2F2F2, 0xFFE0E0E1, 0xFF1F2328, 0xFF8F9194, 0xFFC9CACB, 0xFFA475F9},
            {0xFFFFFFFF, 0xFFF2F2F2, 0xFFE0E0E1, 0xFF1F2328, 0xFF8F9194, 0xFFC9CACB, 0xFFA475F9},
            {0xFF13773D, 0xFF217E43, 0xFF34884C, 0xFFFFF0A5, 0xFF89B471, 0xFF4C9456, 0xFF55FFFF},
            {0xFFFBF1C7, 0xFFF0E6BE, 0xFFE0D7B3, 0xFF3C3836, 0xFF9C947E, 0xFFCDC5A4, 0xFFB16286},
            {0xFF282828, 0xFF32312F, 0xFF403D38, 0xFFD4BE98, 0xFF7E7360, 0xFF514C43, 0xFFD3869B},
            {0xFF1C1E26, 0xFF272931, 0xFF36383F, 0xFFD5D8DA, 0xFF787B80, 0xFF484B51, 0xFF3FC4DE},
            {0xFFEA3323, 0xFFEB3F30, 0xFFED5042, 0xFFFFFFFF, 0xFFF49991, 0xFFEF6458, 0xFFFFFF54},
            {0xFF161821, 0xFF21232C, 0xFF2F313A, 0xFFC6C8D1, 0xFF6E7079, 0xFF40424B, 0xFFD2D4DE},
            {0xFFE8E9EC, 0xFFDDDEE2, 0xFFCFD0D6, 0xFF33374C, 0xFF8E909C, 0xFFBDBEC6, 0xFFCC3768},
            {0xFF2C1D16, 0xFF392818, 0xFF4A361A, 0xFFFFCC2F, 0xFF967422, 0xFF5F471C, 0xFFE500E5},
            {0xFF181616, 0xFF222120, 0xFF302F2E, 0xFFC5C9C5, 0xFF6E706E, 0xFF424140, 0xFFC5C9C5},
            {0xFFF2ECBC, 0xFFE9E3B7, 0xFFDCD7B0, 0xFF545464, 0xFFA3A090, 0xFFCCC8A7, 0xFF1F1F28},
            {0xFF030D18, 0xFF110D24, 0xFF240C34, 0xFFF106E3, 0xFF7A0A7E, 0xFF3C0B49, 0xFFFFFED5},
            {0xFFFEF49C, 0xFFEFE593, 0xFFDAD286, 0xFF000000, 0xFF7F7A4E, 0xFFC1B977, 0xFFE500E5},
            {0xFF0F111A, 0xFF171922, 0xFF21232D, 0xFF8F93A2, 0xFF4F525E, 0xFF2E303B, 0xFFFFFFFF},
            {0xFF292522, 0xFF35302D, 0xFF443F3B, 0xFFECE1D7, 0xFF8A837C, 0xFF58524D, 0xFFCF9BC2},
            {0xFFF1F1F1, 0xFFE8E7E6, 0xFFDBD9D7, 0xFF54433A, 0xFFA29A96, 0xFFCBC7C5, 0xFF54433A},
            {0xFF120B2E, 0xFF20183B, 0xFF332A4B, 0xFFFCE7FF, 0xFF877996, 0xFF4A4060, 0xFFFF7847},
            {0xFF080808, 0xFF131313, 0xFF212121, 0xFFBDBDBD, 0xFF626262, 0xFF333333, 0xFF36C692},
            {0xFFFFFF54, 0xFFF0F04F, 0xFFDBDB48, 0xFF000000, 0xFF80802A, 0xFFC2C240, 0xFFEA3323},
            {0xFF011627, 0xFF0E2233, 0xFF1F3242, 0xFFD6DEEB, 0xFF6C7A89, 0xFF344656, 0xFFFFEB95},
            {0xFF192330, 0xFF242D3A, 0xFF323B46, 0xFFCDCECF, 0xFF737880, 0xFF444C56, 0xFFC94F6D},
            {0xFFE5E9F0, 0xFFDBDFE7, 0xFFCED2DB, 0xFF414858, 0xFF9398A4, 0xFFBEC2CC, 0xFFBF616A},
            {0xFFDFDBC3, 0xFFD5D0B9, 0xFFC8C1AC, 0xFF3B2322, 0xFF8D7F72, 0xFFB8AF9C, 0xFFCC00CC},
            {0xFF224FBC, 0xFF2F5AC0, 0xFF4168C5, 0xFFFFFFFF, 0xFF90A7DE, 0xFF5779CC, 0xFFE5E500},
            {0xFF162C35, 0xFF20353E, 0xFF2E414A, 0xFFC0C5CE, 0xFF6B7882, 0xFF3F515A, 0xFF5FB3B3},
            {0xFF161616, 0xFF232324, 0xFF353536, 0xFFF2F4F8, 0xFF848587, 0xFF4B4B4C, 0xFFFF4297},
            {0xFF2F1E2E, 0xFF362635, 0xFF3F303D, 0xFFA39E9B, 0xFF695E64, 0xFF4B3D48, 0xFFE7E9DB},
            {0xFF1A1E28, 0xFF222732, 0xFF2E323F, 0xFFA6ACCD, 0xFF60657A, 0xFF3C4050, 0xFFFFFAC2},
            {0xFF052454, 0xFF13315E, 0xFF27416B, 0xFFF6F6F7, 0xFF7E8DA6, 0xFF3F567B, 0xFFFEFE45},
            {0xFF21084A, 0xFF2E1754, 0xFF402A62, 0xFFFFFBF6, 0xFF9082A0, 0xFF564273, 0xFFAC7BF0},
            {0xFF762423, 0xFF7E3130, 0xFF894342, 0xFFFFFFFF, 0xFFBA9291, 0xFF975958, 0xFFBEB86B},
            {0xFF7A251E, 0xFF802F26, 0xFF873C31, 0xFFD7C9A7, 0xFFA87762, 0xFF904C3F, 0xFFFF55FF},
            {0xFF232136, 0xFF2E2C41, 0xFF3D3B51, 0xFFE0DEF4, 0xFF828095, 0xFF504E64, 0xFFF6C177},
            {0xFFFAF4ED, 0xFFF0EAE6, 0xFFE3DDDD, 0xFF575279, 0xFFA8A3B3, 0xFFD3CDD1, 0xFF575279},
            {0xFF18131E, 0xFF241929, 0xFF342239, 0xFFDD7BDC, 0xFF7A477D, 0xFF472C4C, 0xFFF59574},
            {0xFF243435, 0xFF2F3F3F, 0xFF3D4D4B, 0xFFD4E7D4, 0xFF7C8E84, 0xFF4E5F5B, 0xFFFAE79D},
            {0xFF103C48, 0xFF19444F, 0xFF264E58, 0xFFADBCBC, 0xFF5E7C82, 0xFF365B64, 0xFFCAD8D9},
            {0xFF1E1D40, 0xFF2C2B4B, 0xFF3E3D5B, 0xFFFFFFFF, 0xFF8E8EA0, 0xFF54536E, 0xFFF1D000},
            {0xFF1E1F29, 0xFF2A2B34, 0xFF3B3C43, 0xFFEBECE6, 0xFF848688, 0xFF4F5056, 0xFFFC4CB4},
            {0xFFFDF6E3, 0xFFF4EFDD, 0xFFE8E5D6, 0xFF657B83, 0xFFB1B8B3, 0xFFD9D8CC, 0xFF002B36},
            {0xFF2C2E34, 0xFF37393E, 0xFF45474C, 0xFFE2E2E3, 0xFF87888C, 0xFF58595E, 0xFF9ED072},
            {0xFF20242D, 0xFF292D36, 0xFF353942, 0xFFB3B8C3, 0xFF6A6E78, 0xFF434851, 0xFFFFFFFF},
            {0xFF372920, 0xFF3E3026, 0xFF48392F, 0xFFB19B89, 0xFF746254, 0xFF544439, 0xFF468336},
            {0xFF000000, 0xFF0D0D0C, 0xFF1F1E1C, 0xFFDAD9C7, 0xFF6D6C64, 0xFF343430, 0xFF19CDE6},
            {0xFFE1E2E7, 0xFFD7DAE5, 0xFFC9D0E1, 0xFF3760BF, 0xFF8CA1D3, 0xFFB8C3DD, 0xFF8C6C3E},
            {0xFF222436, 0xFF2C2E41, 0xFF393C51, 0xFFC8D3F5, 0xFF757C96, 0xFF4A4E64, 0xFFFFC777},
            {0xFF002451, 0xFF0F315B, 0xFF244369, 0xFFFFFFFF, 0xFF8092A8, 0xFF3D597B, 0xFFFFFFFF},
            {0xFF300A24, 0xFF3B1830, 0xFF4B2A40, 0xFFEEEEEC, 0xFF8F7C88, 0xFF5E4154, 0xFFFCE94F},
            {0xFFFF8CD9, 0xFFF084CD, 0xFFDD7ABC, 0xFF0B0B0B, 0xFF854C72, 0xFFC46DA8, 0xFFA80F20},
            {0xFF1F0E1C, 0xFF2C1A28, 0xFF3D2B39, 0xFFF2DCEA, 0xFF887583, 0xFF523F4D, 0xFFE8964F},
    };
    /** Current colours (the names are historical: GREEN = main text, AMBER = accent). */
    static int VOID, CELL, LIT, SEL, GREEN, DIM, RULE, AMBER;
    static String name;
    static { apply("phosphor"); }

    /** "+swap" after a name = the same theme with its text and accent colours swapped. */
    static final String SWAP = "+swap";

    static int index(String n) {
        if (n != null && n.endsWith(SWAP)) n = n.substring(0, n.length() - SWAP.length());
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(n)) return i;
        return 0;
    }

    static boolean swapped(String n) { return n != null && n.endsWith(SWAP); }

    /** "photo:" + 7 hex colours = colours taken from the home screen's background photo. */
    static final String PHOTO = "photo:";

    static void apply(String n) {
        boolean sw = swapped(n);
        String base = sw ? n.substring(0, n.length() - SWAP.length()) : n;
        int[] c = P[index(n)];
        name = NAMES[index(n)] + (sw ? SWAP : "");
        if (base != null && base.startsWith(PHOTO)) {
            try {
                String[] h = base.substring(PHOTO.length()).split(",");
                int[] q = new int[7];
                for (int i = 0; i < 7; i++) q[i] = 0xFF000000 | Integer.parseInt(h[i], 16);
                c = q;
                name = n;
            } catch (Exception ignored) { }
        }
        VOID = c[0]; CELL = c[1]; LIT = c[2]; SEL = c[2]; GREEN = c[3]; DIM = c[4]; RULE = c[5]; AMBER = c[6];
        if (sw) {                                        // accent becomes the text; the in-between shades follow it
            GREEN = c[6]; AMBER = c[3];
            CELL = mix(c[0], GREEN, 0.06f); LIT = SEL = mix(c[0], GREEN, 0.14f);
            RULE = mix(c[0], GREEN, 0.24f); DIM = mix(c[0], GREEN, 0.5f);
        }
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + ((((b >> 16) & 255) - ((a >> 16) & 255)) * t));
        int g = (int) (((a >> 8) & 255) + ((((b >> 8) & 255) - ((a >> 8) & 255)) * t));
        int bl = (int) ((a & 255) + (((b & 255) - (a & 255)) * t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** True for light themes (status bar icons etc. must be dark). */
    static boolean light() { return (((VOID >> 16) & 255) * 299 + ((VOID >> 8) & 255) * 587 + (VOID & 255) * 114) / 1000 > 128; }

    /** The chosen theme's name, from the home app (phosphor if it can't be read). */
    static String chosen(Context c) {
        if (c.getPackageName().equals("dumb_phone.home"))
            return c.getSharedPreferences("theme", Context.MODE_PRIVATE).getString("name", "phosphor");
        try (android.database.Cursor q = c.getContentResolver().query(
                android.net.Uri.parse("content://dumb_phone.theme/current"), null, null, null, null)) {
            if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (Exception ignored) { }
        return "phosphor";
    }

    static void load(Context c) { apply(chosen(c)); }

    static boolean changed(Context c) { return !chosen(c).equals(name); }
}

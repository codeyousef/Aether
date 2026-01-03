package codes.yousef.aether.admin

/**
 * Modern CMS-style theme for Aether Admin.
 * Inspired by Tailwind CSS design tokens.
 */
object AdminTheme {
    object Colors {
        // Background colors
        const val BG_PRIMARY = "#0f172a"      // Slate 900
        const val BG_SECONDARY = "#1e293b"    // Slate 800
        const val BG_CARD = "#1e293b"         // Slate 800
        const val BG_CARD_HOVER = "#334155"   // Slate 700
        const val BG_INPUT = "#1e293b"        // Slate 800
        const val BG_SURFACE = "#ffffff"      // White for content area
        
        // Text colors
        const val TEXT_PRIMARY = "#f8fafc"    // Slate 50
        const val TEXT_SECONDARY = "#94a3b8"  // Slate 400
        const val TEXT_MUTED = "#64748b"      // Slate 500
        const val TEXT_DARK = "#1e293b"       // Slate 800
        
        // Accent colors
        const val PRIMARY = "#3b82f6"         // Blue 500
        const val PRIMARY_HOVER = "#2563eb"   // Blue 600
        const val PRIMARY_LIGHT = "#60a5fa"   // Blue 400
        
        const val SUCCESS = "#22c55e"         // Green 500
        const val SUCCESS_BG = "#052e16"      // Green 950
        const val WARNING = "#f59e0b"         // Amber 500
        const val WARNING_BG = "#451a03"      // Amber 950
        const val ERROR = "#ef4444"           // Red 500
        const val ERROR_BG = "#450a0a"        // Red 950
        const val INFO = "#06b6d4"            // Cyan 500
        
        // Border colors
        const val BORDER = "#334155"          // Slate 700
        const val BORDER_LIGHT = "#475569"    // Slate 600
    }
    
    object Spacing {
        const val xxs = "4px"
        const val xs = "8px"
        const val sm = "12px"
        const val md = "16px"
        const val lg = "24px"
        const val xl = "32px"
        const val xxl = "48px"
    }
    
    object Radius {
        const val sm = "4px"
        const val md = "8px"
        const val lg = "12px"
        const val full = "9999px"
    }
    
    object Shadow {
        const val sm = "0 1px 2px 0 rgb(0 0 0 / 0.05)"
        const val md = "0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1)"
        const val lg = "0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.1)"
    }
    
    object Typography {
        const val fontFamily = "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
        const val fontSizeXs = "12px"
        const val fontSizeSm = "14px"
        const val fontSizeMd = "16px"
        const val fontSizeLg = "18px"
        const val fontSizeXl = "20px"
        const val fontSize2xl = "24px"
        const val fontSize3xl = "30px"
        
        const val fontWeightNormal = "400"
        const val fontWeightMedium = "500"
        const val fontWeightSemibold = "600"
        const val fontWeightBold = "700"
    }
    
    object Transition {
        const val fast = "150ms ease"
        const val normal = "200ms ease"
    }
    
    /**
     * Generates the CSS stylesheet for the admin theme.
     */
    fun generateStyles(): String = """
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');
        
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }
        
        body {
            font-family: ${Typography.fontFamily};
            font-size: ${Typography.fontSizeSm};
            background-color: ${Colors.BG_SURFACE};
            color: ${Colors.TEXT_DARK};
            line-height: 1.5;
        }
        
        /* Layout */
        .admin-layout {
            display: flex;
            min-height: 100vh;
        }
        
        .admin-sidebar {
            width: 260px;
            background: ${Colors.BG_PRIMARY};
            color: ${Colors.TEXT_PRIMARY};
            display: flex;
            flex-direction: column;
            position: fixed;
            top: 0;
            left: 0;
            bottom: 0;
            overflow-y: auto;
        }
        
        .admin-sidebar-header {
            padding: ${Spacing.lg};
            border-bottom: 1px solid ${Colors.BORDER};
        }
        
        .admin-sidebar-logo {
            font-size: ${Typography.fontSizeXl};
            font-weight: ${Typography.fontWeightBold};
            color: ${Colors.TEXT_PRIMARY};
            text-decoration: none;
            display: flex;
            align-items: center;
            gap: ${Spacing.sm};
        }
        
        .admin-sidebar-logo svg {
            width: 32px;
            height: 32px;
            color: ${Colors.PRIMARY};
        }
        
        .admin-sidebar-nav {
            flex: 1;
            padding: ${Spacing.md};
        }
        
        .admin-nav-section {
            margin-bottom: ${Spacing.lg};
        }
        
        .admin-nav-section-title {
            font-size: ${Typography.fontSizeXs};
            font-weight: ${Typography.fontWeightSemibold};
            color: ${Colors.TEXT_MUTED};
            text-transform: uppercase;
            letter-spacing: 0.05em;
            padding: ${Spacing.xs} ${Spacing.sm};
            margin-bottom: ${Spacing.xs};
        }
        
        .admin-nav-item {
            display: flex;
            align-items: center;
            gap: ${Spacing.sm};
            padding: ${Spacing.sm} ${Spacing.md};
            color: ${Colors.TEXT_SECONDARY};
            text-decoration: none;
            border-radius: ${Radius.md};
            transition: all ${Transition.fast};
            font-weight: ${Typography.fontWeightMedium};
        }
        
        .admin-nav-item:hover {
            background: ${Colors.BG_SECONDARY};
            color: ${Colors.TEXT_PRIMARY};
        }
        
        .admin-nav-item.active {
            background: ${Colors.PRIMARY};
            color: ${Colors.TEXT_PRIMARY};
        }
        
        .admin-nav-item svg {
            width: 20px;
            height: 20px;
            flex-shrink: 0;
        }
        
        /* Main content */
        .admin-main {
            margin-left: 260px;
            flex: 1;
            min-height: 100vh;
        }
        
        .admin-header {
            background: white;
            border-bottom: 1px solid #e2e8f0;
            padding: ${Spacing.md} ${Spacing.xl};
            display: flex;
            justify-content: space-between;
            align-items: center;
            position: sticky;
            top: 0;
            z-index: 100;
        }
        
        .admin-header-title {
            font-size: ${Typography.fontSizeLg};
            font-weight: ${Typography.fontWeightSemibold};
            color: ${Colors.TEXT_DARK};
        }
        
        .admin-header-actions {
            display: flex;
            gap: ${Spacing.sm};
        }
        
        .admin-content {
            padding: ${Spacing.xl};
        }
        
        /* Cards */
        .admin-card {
            background: white;
            border: 1px solid #e2e8f0;
            border-radius: ${Radius.lg};
            overflow: hidden;
        }
        
        .admin-card-header {
            padding: ${Spacing.lg};
            border-bottom: 1px solid #e2e8f0;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        
        .admin-card-title {
            font-size: ${Typography.fontSizeMd};
            font-weight: ${Typography.fontWeightSemibold};
            color: ${Colors.TEXT_DARK};
        }
        
        .admin-card-body {
            padding: ${Spacing.lg};
        }
        
        /* Stats Cards */
        .admin-stats-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: ${Spacing.lg};
            margin-bottom: ${Spacing.xl};
        }
        
        .admin-stat-card {
            background: white;
            border: 1px solid #e2e8f0;
            border-radius: ${Radius.lg};
            padding: ${Spacing.lg};
        }
        
        .admin-stat-label {
            font-size: ${Typography.fontSizeSm};
            color: ${Colors.TEXT_MUTED};
            margin-bottom: ${Spacing.xs};
        }
        
        .admin-stat-value {
            font-size: ${Typography.fontSize2xl};
            font-weight: ${Typography.fontWeightBold};
            color: ${Colors.TEXT_DARK};
        }
        
        /* Tables */
        .admin-table {
            width: 100%;
            border-collapse: collapse;
        }
        
        .admin-table th {
            text-align: left;
            padding: ${Spacing.md};
            font-size: ${Typography.fontSizeXs};
            font-weight: ${Typography.fontWeightSemibold};
            color: ${Colors.TEXT_MUTED};
            text-transform: uppercase;
            letter-spacing: 0.05em;
            background: #f8fafc;
            border-bottom: 1px solid #e2e8f0;
        }
        
        .admin-table td {
            padding: ${Spacing.md};
            border-bottom: 1px solid #e2e8f0;
            color: ${Colors.TEXT_DARK};
        }
        
        .admin-table tr:hover td {
            background: #f8fafc;
        }
        
        .admin-table-link {
            color: ${Colors.PRIMARY};
            text-decoration: none;
            font-weight: ${Typography.fontWeightMedium};
        }
        
        .admin-table-link:hover {
            text-decoration: underline;
        }
        
        /* Buttons */
        .admin-btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: ${Spacing.xs};
            padding: ${Spacing.sm} ${Spacing.md};
            font-size: ${Typography.fontSizeSm};
            font-weight: ${Typography.fontWeightMedium};
            border-radius: ${Radius.md};
            border: none;
            cursor: pointer;
            text-decoration: none;
            transition: all ${Transition.fast};
        }
        
        .admin-btn-primary {
            background: ${Colors.PRIMARY};
            color: white;
        }
        
        .admin-btn-primary:hover {
            background: ${Colors.PRIMARY_HOVER};
        }
        
        .admin-btn-secondary {
            background: #f1f5f9;
            color: ${Colors.TEXT_DARK};
        }
        
        .admin-btn-secondary:hover {
            background: #e2e8f0;
        }
        
        .admin-btn-danger {
            background: ${Colors.ERROR};
            color: white;
        }
        
        .admin-btn-danger:hover {
            background: #dc2626;
        }
        
        /* Forms */
        .admin-form-group {
            margin-bottom: ${Spacing.lg};
        }
        
        .admin-form-label {
            display: block;
            font-size: ${Typography.fontSizeSm};
            font-weight: ${Typography.fontWeightMedium};
            color: ${Colors.TEXT_DARK};
            margin-bottom: ${Spacing.xs};
        }
        
        .admin-form-input {
            width: 100%;
            padding: ${Spacing.sm} ${Spacing.md};
            font-size: ${Typography.fontSizeSm};
            border: 1px solid #cbd5e1;
            border-radius: ${Radius.md};
            background: white;
            transition: all ${Transition.fast};
        }
        
        .admin-form-input:focus {
            outline: none;
            border-color: ${Colors.PRIMARY};
            box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
        }
        
        .admin-form-textarea {
            resize: vertical;
            min-height: 100px;
        }
        
        .admin-form-checkbox {
            display: flex;
            align-items: center;
            gap: ${Spacing.sm};
        }
        
        .admin-form-checkbox input {
            width: 18px;
            height: 18px;
            accent-color: ${Colors.PRIMARY};
        }
        
        .admin-form-help {
            font-size: ${Typography.fontSizeXs};
            color: ${Colors.TEXT_MUTED};
            margin-top: ${Spacing.xxs};
        }
        
        .admin-form-error {
            color: ${Colors.ERROR};
            font-size: ${Typography.fontSizeXs};
            margin-top: ${Spacing.xxs};
        }
        
        /* Search and Filters */
        .admin-toolbar {
            display: flex;
            gap: ${Spacing.md};
            margin-bottom: ${Spacing.lg};
            flex-wrap: wrap;
        }
        
        .admin-search {
            flex: 1;
            min-width: 200px;
            max-width: 400px;
            position: relative;
        }
        
        .admin-search-input {
            width: 100%;
            padding: ${Spacing.sm} ${Spacing.md};
            padding-left: 40px;
            font-size: ${Typography.fontSizeSm};
            border: 1px solid #cbd5e1;
            border-radius: ${Radius.md};
            background: white;
        }
        
        .admin-search-icon {
            position: absolute;
            left: 12px;
            top: 50%;
            transform: translateY(-50%);
            color: ${Colors.TEXT_MUTED};
            width: 18px;
            height: 18px;
        }
        
        /* Filter sidebar */
        .admin-list-layout {
            display: flex;
            gap: ${Spacing.xl};
        }
        
        .admin-list-main {
            flex: 1;
        }
        
        .admin-filter-sidebar {
            width: 240px;
            flex-shrink: 0;
        }
        
        .admin-filter-card {
            background: white;
            border: 1px solid #e2e8f0;
            border-radius: ${Radius.lg};
            overflow: hidden;
        }
        
        .admin-filter-title {
            font-size: ${Typography.fontSizeSm};
            font-weight: ${Typography.fontWeightSemibold};
            color: ${Colors.TEXT_DARK};
            padding: ${Spacing.md};
            background: #f8fafc;
            border-bottom: 1px solid #e2e8f0;
        }
        
        .admin-filter-section {
            padding: ${Spacing.md};
            border-bottom: 1px solid #e2e8f0;
        }
        
        .admin-filter-section:last-child {
            border-bottom: none;
        }
        
        .admin-filter-section-title {
            font-size: ${Typography.fontSizeXs};
            font-weight: ${Typography.fontWeightSemibold};
            color: ${Colors.TEXT_MUTED};
            text-transform: uppercase;
            margin-bottom: ${Spacing.sm};
        }
        
        .admin-filter-option {
            display: block;
            padding: ${Spacing.xs} ${Spacing.sm};
            color: ${Colors.TEXT_DARK};
            text-decoration: none;
            border-radius: ${Radius.sm};
            font-size: ${Typography.fontSizeSm};
            transition: all ${Transition.fast};
        }
        
        .admin-filter-option:hover {
            background: #f1f5f9;
        }
        
        .admin-filter-option.active {
            background: ${Colors.PRIMARY};
            color: white;
        }
        
        /* Badges */
        .admin-badge {
            display: inline-flex;
            align-items: center;
            padding: 2px 8px;
            font-size: ${Typography.fontSizeXs};
            font-weight: ${Typography.fontWeightMedium};
            border-radius: ${Radius.full};
        }
        
        .admin-badge-success {
            background: #dcfce7;
            color: #166534;
        }
        
        .admin-badge-warning {
            background: #fef3c7;
            color: #92400e;
        }
        
        .admin-badge-error {
            background: #fee2e2;
            color: #991b1b;
        }
        
        .admin-badge-info {
            background: #e0f2fe;
            color: #075985;
        }
        
        /* Empty state */
        .admin-empty {
            text-align: center;
            padding: ${Spacing.xxl};
            color: ${Colors.TEXT_MUTED};
        }
        
        .admin-empty-icon {
            width: 48px;
            height: 48px;
            margin: 0 auto ${Spacing.md};
            color: #cbd5e1;
        }
        
        .admin-empty-title {
            font-size: ${Typography.fontSizeMd};
            font-weight: ${Typography.fontWeightMedium};
            color: ${Colors.TEXT_DARK};
            margin-bottom: ${Spacing.xs};
        }
        
        /* Breadcrumbs */
        .admin-breadcrumbs {
            display: flex;
            align-items: center;
            gap: ${Spacing.xs};
            font-size: ${Typography.fontSizeSm};
            color: ${Colors.TEXT_MUTED};
            margin-bottom: ${Spacing.md};
        }
        
        .admin-breadcrumbs a {
            color: ${Colors.TEXT_MUTED};
            text-decoration: none;
        }
        
        .admin-breadcrumbs a:hover {
            color: ${Colors.PRIMARY};
        }
        
        .admin-breadcrumbs-separator {
            color: #cbd5e1;
        }
        
        /* Actions dropdown */
        .admin-actions {
            display: flex;
            gap: ${Spacing.xs};
        }
        
        .admin-action-btn {
            padding: ${Spacing.xs};
            border-radius: ${Radius.sm};
            color: ${Colors.TEXT_MUTED};
            background: transparent;
            border: none;
            cursor: pointer;
            transition: all ${Transition.fast};
        }
        
        .admin-action-btn:hover {
            background: #f1f5f9;
            color: ${Colors.TEXT_DARK};
        }
        
        .admin-action-btn.delete:hover {
            background: #fee2e2;
            color: ${Colors.ERROR};
        }
        
        /* Responsive */
        @media (max-width: 768px) {
            .admin-sidebar {
                transform: translateX(-100%);
                transition: transform ${Transition.normal};
            }
            
            .admin-sidebar.open {
                transform: translateX(0);
            }
            
            .admin-main {
                margin-left: 0;
            }
            
            .admin-list-layout {
                flex-direction: column;
            }
            
            .admin-filter-sidebar {
                width: 100%;
            }
        }
    """.trimIndent()
    
    /**
     * SVG icons used in the admin interface.
     */
    object Icons {
        const val dashboard = """<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M3.75 6A2.25 2.25 0 016 3.75h2.25A2.25 2.25 0 0110.5 6v2.25a2.25 2.25 0 01-2.25 2.25H6a2.25 2.25 0 01-2.25-2.25V6zM3.75 15.75A2.25 2.25 0 016 13.5h2.25a2.25 2.25 0 012.25 2.25V18a2.25 2.25 0 01-2.25 2.25H6A2.25 2.25 0 013.75 18v-2.25zM13.5 6a2.25 2.25 0 012.25-2.25H18A2.25 2.25 0 0120.25 6v2.25A2.25 2.25 0 0118 10.5h-2.25a2.25 2.25 0 01-2.25-2.25V6zM13.5 15.75a2.25 2.25 0 012.25-2.25H18a2.25 2.25 0 012.25 2.25V18A2.25 2.25 0 0118 20.25h-2.25A2.25 2.25 0 0113.5 18v-2.25z" /></svg>"""
        const val database = """<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M20.25 6.375c0 2.278-3.694 4.125-8.25 4.125S3.75 8.653 3.75 6.375m16.5 0c0-2.278-3.694-4.125-8.25-4.125S3.75 4.097 3.75 6.375m16.5 0v11.25c0 2.278-3.694 4.125-8.25 4.125s-8.25-1.847-8.25-4.125V6.375m16.5 0v3.75m-16.5-3.75v3.75m16.5 0v3.75C20.25 16.153 16.556 18 12 18s-8.25-1.847-8.25-4.125v-3.75m16.5 0c0 2.278-3.694 4.125-8.25 4.125s-8.25-1.847-8.25-4.125" /></svg>"""
        const val plus = """<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M12 4.5v15m7.5-7.5h-15" /></svg>"""
        const val search = """<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" /></svg>"""
        const val edit = """<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M16.862 4.487l1.687-1.688a1.875 1.875 0 112.652 2.652L10.582 16.07a4.5 4.5 0 01-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 011.13-1.897l8.932-8.931zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0115.75 21H5.25A2.25 2.25 0 013 18.75V8.25A2.25 2.25 0 015.25 6H10" /></svg>"""
        const val trash = """<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M14.74 9l-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 01-2.244 2.077H8.084a2.25 2.25 0 01-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 00-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 013.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 00-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 00-7.5 0" /></svg>"""
        const val chevronRight = """<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M8.25 4.5l7.5 7.5-7.5 7.5" /></svg>"""
        const val folder = """<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M2.25 12.75V12A2.25 2.25 0 014.5 9.75h15A2.25 2.25 0 0121.75 12v.75m-8.69-6.44l-2.12-2.12a1.5 1.5 0 00-1.061-.44H4.5A2.25 2.25 0 002.25 6v12a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9a2.25 2.25 0 00-2.25-2.25h-5.379a1.5 1.5 0 01-1.06-.44z" /></svg>"""
        const val logo = """<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 00-2.456 2.456zM16.894 20.567L16.5 21.75l-.394-1.183a2.25 2.25 0 00-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 001.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 001.423 1.423l1.183.394-1.183.394a2.25 2.25 0 00-1.423 1.423z" /></svg>"""
        const val empty = """<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M20.25 7.5l-.625 10.632a2.25 2.25 0 01-2.247 2.118H6.622a2.25 2.25 0 01-2.247-2.118L3.75 7.5M10 11.25h4M3.375 7.5h17.25c.621 0 1.125-.504 1.125-1.125v-1.5c0-.621-.504-1.125-1.125-1.125H3.375c-.621 0-1.125.504-1.125 1.125v1.5c0 .621.504 1.125 1.125 1.125z" /></svg>"""
    }
}

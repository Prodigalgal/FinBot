import { createTheme } from '@mui/material/styles';

/** Institutional research-terminal tokens (no gradients, max 6px radius). */
const graphite = {
  900: '#0f172a',
  800: '#1e293b',
  700: '#334155',
  600: '#475569',
  500: '#64748b',
  400: '#94a3b8',
  300: '#cbd5e1',
  200: '#e2e8f0',
  100: '#f1f5f9',
  50: '#f8fafc',
} as const;

const cobalt = {
  main: '#1d4ed8',
  dark: '#1e3a8a',
  light: '#dbeafe',
  contrastText: '#ffffff',
} as const;

const semantic = {
  success: { main: '#15803d', dark: '#14532d', light: '#dcfce7', contrastText: '#ffffff' },
  warning: { main: '#b45309', dark: '#92400e', light: '#fef3c7', contrastText: '#ffffff' },
  error: { main: '#b91c1c', dark: '#7f1d1d', light: '#fee2e2', contrastText: '#ffffff' },
  info: { main: '#1d4ed8', dark: '#1e3a8a', light: '#dbeafe', contrastText: '#ffffff' },
} as const;

const borderSubtle = graphite[200];
const borderStrong = graphite[300];
const surfaceMuted = '#f5f7fb';
const focusRing = '0 0 0 2px rgba(29, 78, 216, 0.28)';
const shadowCard = '0 1px 2px rgba(15, 23, 42, 0.04)';

const tabularNumeric = {
  fontVariantNumeric: 'tabular-nums' as const,
  fontFeatureSettings: '"tnum"',
};

export const theme = createTheme({
  palette: {
    mode: 'light',
    background: {
      default: surfaceMuted,
      paper: '#ffffff',
    },
    primary: {
      main: cobalt.main,
      dark: cobalt.dark,
      light: cobalt.light,
      contrastText: cobalt.contrastText,
    },
    secondary: {
      main: '#0f766e',
      dark: '#115e59',
      light: '#ccfbf1',
      contrastText: '#ffffff',
    },
    success: semantic.success,
    warning: semantic.warning,
    error: semantic.error,
    info: semantic.info,
    text: {
      primary: graphite[900],
      secondary: graphite[600],
      disabled: graphite[400],
    },
    divider: borderSubtle,
    action: {
      active: graphite[700],
      hover: 'rgba(15, 23, 42, 0.04)',
      selected: 'rgba(29, 78, 216, 0.08)',
      disabled: graphite[400],
      disabledBackground: graphite[100],
      focus: 'rgba(29, 78, 216, 0.12)',
    },
  },
  shape: {
    borderRadius: 6,
  },
  typography: {
    fontFamily: [
      'Inter',
      'Segoe UI',
      'Roboto',
      'Helvetica Neue',
      'Arial',
      'sans-serif',
    ].join(','),
    h1: { fontSize: 26, fontWeight: 700, lineHeight: 1.2, color: graphite[900], letterSpacing: 0 },
    h2: { fontSize: 20, fontWeight: 700, lineHeight: 1.25, color: graphite[900], letterSpacing: 0 },
    h3: { fontSize: 17, fontWeight: 700, lineHeight: 1.3, color: graphite[900] },
    h4: { fontSize: 15, fontWeight: 700, lineHeight: 1.35, color: graphite[900] },
    h5: { fontSize: 14, fontWeight: 700, lineHeight: 1.4, color: graphite[900] },
    h6: { fontSize: 13, fontWeight: 700, lineHeight: 1.4, color: graphite[900] },
    subtitle1: { fontSize: 14, fontWeight: 600, lineHeight: 1.4, color: graphite[800] },
    subtitle2: { fontSize: 13, fontWeight: 600, lineHeight: 1.4, color: graphite[700] },
    body1: { fontSize: 14, lineHeight: 1.55, color: graphite[900], ...tabularNumeric },
    body2: { fontSize: 13, lineHeight: 1.45, color: graphite[800], ...tabularNumeric },
    button: { fontSize: 13, fontWeight: 600, textTransform: 'none', letterSpacing: 0 },
    caption: { fontSize: 12, lineHeight: 1.35, color: graphite[600], ...tabularNumeric },
    overline: {
      fontSize: 11,
      fontWeight: 600,
      lineHeight: 1.4,
      letterSpacing: 0,
      textTransform: 'uppercase',
      color: graphite[500],
    },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        html: {
          ...tabularNumeric,
        },
        body: {
          backgroundColor: surfaceMuted,
          color: graphite[900],
          ...tabularNumeric,
        },
        '*, *::before, *::after': {
          borderColor: borderSubtle,
        },
      },
    },
    MuiButton: {
      defaultProps: {
        disableElevation: true,
      },
      styleOverrides: {
        root: {
          borderRadius: 6,
          minHeight: 32,
          padding: '4px 12px',
          fontWeight: 600,
          boxShadow: 'none',
          textTransform: 'none',
          '&:hover': {
            boxShadow: 'none',
          },
          '&.Mui-focusVisible': {
            boxShadow: focusRing,
          },
          '&.Mui-disabled': {
            opacity: 0.55,
          },
        },
        sizeSmall: {
          minHeight: 28,
          padding: '2px 10px',
          fontSize: 12,
        },
        sizeLarge: {
          minHeight: 36,
          padding: '6px 16px',
          fontSize: 14,
        },
        contained: {
          border: '1px solid transparent',
        },
        containedPrimary: {
          backgroundColor: cobalt.main,
          color: cobalt.contrastText,
          '&:hover': {
            backgroundColor: cobalt.dark,
          },
        },
        outlined: {
          borderColor: borderStrong,
          backgroundColor: '#ffffff',
          color: graphite[800],
          '&:hover': {
            borderColor: graphite[400],
            backgroundColor: graphite[50],
          },
        },
        outlinedPrimary: {
          borderColor: cobalt.main,
          color: cobalt.main,
          backgroundColor: '#ffffff',
          '&:hover': {
            borderColor: cobalt.dark,
            backgroundColor: cobalt.light,
          },
        },
        text: {
          color: graphite[700],
          '&:hover': {
            backgroundColor: 'rgba(15, 23, 42, 0.04)',
          },
        },
        textPrimary: {
          color: cobalt.main,
          '&:hover': {
            backgroundColor: cobalt.light,
          },
        },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          color: graphite[700],
          padding: 6,
          '&:hover': {
            backgroundColor: 'rgba(15, 23, 42, 0.04)',
          },
          '&.Mui-focusVisible': {
            boxShadow: focusRing,
          },
          '&.Mui-disabled': {
            color: graphite[400],
          },
        },
        sizeSmall: {
          padding: 4,
        },
        sizeLarge: {
          padding: 8,
        },
      },
    },
    MuiPaper: {
      defaultProps: {
        elevation: 0,
      },
      styleOverrides: {
        root: {
          borderRadius: 6,
          backgroundImage: 'none',
          color: graphite[900],
        },
        outlined: {
          border: `1px solid ${borderSubtle}`,
          backgroundColor: '#ffffff',
        },
        elevation1: {
          boxShadow: shadowCard,
          border: `1px solid ${borderSubtle}`,
        },
      },
    },
    MuiCard: {
      defaultProps: {
        elevation: 0,
        variant: 'outlined',
      },
      styleOverrides: {
        root: {
          borderRadius: 6,
          boxShadow: 'none',
          border: `1px solid ${borderSubtle}`,
          backgroundColor: '#ffffff',
          backgroundImage: 'none',
        },
      },
    },
    MuiCardContent: {
      styleOverrides: {
        root: {
          padding: 16,
          '&:last-child': {
            paddingBottom: 16,
          },
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          height: 24,
          fontWeight: 600,
          fontSize: 12,
          ...tabularNumeric,
          border: `1px solid ${borderSubtle}`,
          backgroundColor: graphite[50],
          color: graphite[800],
          '&.Mui-disabled': {
            opacity: 0.55,
          },
        },
        sizeSmall: {
          height: 22,
          fontSize: 11,
        },
        filled: {
          borderColor: 'transparent',
        },
        outlined: {
          backgroundColor: '#ffffff',
        },
        colorPrimary: {
          backgroundColor: cobalt.light,
          color: cobalt.dark,
          borderColor: 'rgba(29, 78, 216, 0.22)',
        },
        colorSuccess: {
          backgroundColor: semantic.success.light,
          color: semantic.success.dark,
          borderColor: 'rgba(21, 128, 61, 0.22)',
        },
        colorWarning: {
          backgroundColor: semantic.warning.light,
          color: semantic.warning.dark,
          borderColor: 'rgba(180, 83, 9, 0.22)',
        },
        colorError: {
          backgroundColor: semantic.error.light,
          color: semantic.error.dark,
          borderColor: 'rgba(185, 28, 28, 0.22)',
        },
        colorInfo: {
          backgroundColor: semantic.info.light,
          color: semantic.info.dark,
          borderColor: 'rgba(29, 78, 216, 0.22)',
        },
        label: {
          paddingLeft: 8,
          paddingRight: 8,
        },
        deleteIcon: {
          fontSize: 16,
          color: graphite[500],
          '&:hover': {
            color: graphite[800],
          },
        },
      },
    },
    MuiTable: {
      defaultProps: {
        size: 'small',
      },
      styleOverrides: {
        root: {
          ...tabularNumeric,
          borderCollapse: 'separate',
          borderSpacing: 0,
        },
      },
    },
    MuiTableHead: {
      styleOverrides: {
        root: {
          backgroundColor: graphite[50],
          '& .MuiTableCell-root': {
            color: graphite[600],
            fontWeight: 600,
            fontSize: 12,
            lineHeight: 1.35,
            borderBottom: `1px solid ${borderSubtle}`,
            backgroundColor: graphite[50],
          },
        },
      },
    },
    MuiTableBody: {
      styleOverrides: {
        root: {
          '& .MuiTableRow-root:hover': {
            backgroundColor: 'rgba(15, 23, 42, 0.02)',
          },
        },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          '&.Mui-selected': {
            backgroundColor: 'rgba(29, 78, 216, 0.06)',
            '&:hover': {
              backgroundColor: 'rgba(29, 78, 216, 0.09)',
            },
          },
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          borderBottom: `1px solid ${borderSubtle}`,
          padding: '8px 12px',
          fontSize: 13,
          lineHeight: 1.4,
          color: graphite[800],
          ...tabularNumeric,
        },
        head: {
          padding: '8px 12px',
        },
        sizeSmall: {
          padding: '6px 10px',
        },
        stickyHeader: {
          backgroundColor: graphite[50],
          backgroundImage: 'none',
        },
      },
    },
    MuiTableContainer: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          border: `1px solid ${borderSubtle}`,
          backgroundColor: '#ffffff',
        },
      },
    },
    MuiTabs: {
      styleOverrides: {
        root: {
          minHeight: 36,
          borderBottom: `1px solid ${borderSubtle}`,
        },
        indicator: {
          height: 2,
          borderRadius: 0,
          backgroundColor: cobalt.main,
        },
        flexContainer: {
          gap: 0,
        },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: {
          minHeight: 36,
          minWidth: 0,
          padding: '8px 14px',
          fontSize: 13,
          fontWeight: 600,
          textTransform: 'none',
          color: graphite[600],
          '&.Mui-selected': {
            color: cobalt.main,
            fontWeight: 700,
          },
          '&.Mui-disabled': {
            color: graphite[400],
          },
          '&.Mui-focusVisible': {
            outline: 'none',
            boxShadow: focusRing,
          },
          '&:hover': {
            color: graphite[900],
            backgroundColor: 'rgba(15, 23, 42, 0.03)',
          },
        },
      },
    },
    MuiTextField: {
      defaultProps: {
        size: 'small',
        variant: 'outlined',
      },
    },
    MuiInputBase: {
      styleOverrides: {
        root: {
          fontSize: 13,
          ...tabularNumeric,
          '&.Mui-disabled': {
            backgroundColor: graphite[50],
          },
        },
        input: {
          ...tabularNumeric,
          '&::placeholder': {
            color: graphite[400],
            opacity: 1,
          },
        },
      },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          backgroundColor: '#ffffff',
          '& .MuiOutlinedInput-notchedOutline': {
            borderColor: borderStrong,
          },
          '&:hover .MuiOutlinedInput-notchedOutline': {
            borderColor: graphite[400],
          },
          '&.Mui-focused .MuiOutlinedInput-notchedOutline': {
            borderColor: cobalt.main,
            borderWidth: 1,
          },
          '&.Mui-focused': {
            boxShadow: focusRing,
          },
          '&.Mui-error .MuiOutlinedInput-notchedOutline': {
            borderColor: semantic.error.main,
          },
          '&.Mui-disabled': {
            backgroundColor: graphite[50],
            '& .MuiOutlinedInput-notchedOutline': {
              borderColor: borderSubtle,
            },
          },
        },
        input: {
          padding: '8px 12px',
          height: 'auto',
        },
        inputSizeSmall: {
          padding: '6px 10px',
        },
        adornedStart: {
          paddingLeft: 10,
        },
        adornedEnd: {
          paddingRight: 10,
        },
      },
    },
    MuiInputLabel: {
      styleOverrides: {
        root: {
          fontSize: 13,
          color: graphite[600],
          '&.Mui-focused': {
            color: cobalt.main,
          },
          '&.Mui-disabled': {
            color: graphite[400],
          },
        },
        sizeSmall: {
          fontSize: 13,
        },
      },
    },
    MuiFormHelperText: {
      styleOverrides: {
        root: {
          fontSize: 12,
          marginLeft: 2,
          ...tabularNumeric,
        },
      },
    },
    MuiSelect: {
      defaultProps: {
        size: 'small',
      },
      styleOverrides: {
        select: {
          ...tabularNumeric,
          minHeight: '1.25em',
        },
        icon: {
          color: graphite[500],
        },
      },
    },
    MuiMenu: {
      styleOverrides: {
        paper: {
          borderRadius: 6,
          border: `1px solid ${borderSubtle}`,
          boxShadow: '0 4px 16px rgba(15, 23, 42, 0.08)',
          backgroundImage: 'none',
        },
        list: {
          padding: 4,
        },
      },
    },
    MuiMenuItem: {
      styleOverrides: {
        root: {
          borderRadius: 4,
          minHeight: 34,
          fontSize: 13,
          ...tabularNumeric,
          '&.Mui-selected': {
            backgroundColor: 'rgba(29, 78, 216, 0.08)',
            '&:hover': {
              backgroundColor: 'rgba(29, 78, 216, 0.12)',
            },
          },
          '&.Mui-focusVisible': {
            backgroundColor: 'rgba(15, 23, 42, 0.04)',
          },
          '&.Mui-disabled': {
            opacity: 0.5,
          },
        },
      },
    },
    MuiAccordion: {
      defaultProps: {
        disableGutters: true,
        elevation: 0,
      },
      styleOverrides: {
        root: {
          borderRadius: 6,
          border: `1px solid ${borderSubtle}`,
          backgroundColor: '#ffffff',
          backgroundImage: 'none',
          boxShadow: 'none',
          '&:before': {
            display: 'none',
          },
          '&.Mui-expanded': {
            margin: 0,
          },
          '&.Mui-disabled': {
            backgroundColor: graphite[50],
          },
        },
        rounded: {
          borderRadius: 6,
          '&:first-of-type': {
            borderRadius: 6,
          },
          '&:last-of-type': {
            borderRadius: 6,
          },
        },
      },
    },
    MuiAccordionSummary: {
      styleOverrides: {
        root: {
          minHeight: 40,
          padding: '0 12px',
          '&.Mui-expanded': {
            minHeight: 40,
          },
          '&.Mui-focusVisible': {
            backgroundColor: 'rgba(29, 78, 216, 0.06)',
          },
        },
        content: {
          margin: '8px 0',
          '&.Mui-expanded': {
            margin: '8px 0',
          },
        },
        expandIconWrapper: {
          color: graphite[500],
        },
      },
    },
    MuiAccordionDetails: {
      styleOverrides: {
        root: {
          padding: '0 12px 12px',
          borderTop: `1px solid ${borderSubtle}`,
        },
      },
    },
    MuiAlert: {
      defaultProps: {
        variant: 'outlined',
      },
      styleOverrides: {
        root: {
          borderRadius: 6,
          padding: '8px 12px',
          fontSize: 13,
          alignItems: 'flex-start',
          backgroundImage: 'none',
        },
        standardSuccess: {
          backgroundColor: semantic.success.light,
          color: semantic.success.dark,
        },
        standardWarning: {
          backgroundColor: semantic.warning.light,
          color: semantic.warning.dark,
        },
        standardError: {
          backgroundColor: semantic.error.light,
          color: semantic.error.dark,
        },
        standardInfo: {
          backgroundColor: semantic.info.light,
          color: semantic.info.dark,
        },
        outlinedSuccess: {
          borderColor: 'rgba(21, 128, 61, 0.35)',
          backgroundColor: '#ffffff',
          color: semantic.success.dark,
        },
        outlinedWarning: {
          borderColor: 'rgba(180, 83, 9, 0.35)',
          backgroundColor: '#ffffff',
          color: semantic.warning.dark,
        },
        outlinedError: {
          borderColor: 'rgba(185, 28, 28, 0.35)',
          backgroundColor: '#ffffff',
          color: semantic.error.dark,
        },
        outlinedInfo: {
          borderColor: 'rgba(29, 78, 216, 0.35)',
          backgroundColor: '#ffffff',
          color: semantic.info.dark,
        },
        icon: {
          padding: '2px 0 0',
          marginRight: 10,
          fontSize: 18,
        },
        message: {
          padding: '2px 0',
          ...tabularNumeric,
        },
        action: {
          paddingTop: 0,
          marginRight: 0,
        },
      },
    },
    MuiTooltip: {
      defaultProps: {
        arrow: true,
        enterDelay: 400,
      },
      styleOverrides: {
        tooltip: {
          borderRadius: 4,
          backgroundColor: graphite[900],
          color: '#ffffff',
          fontSize: 12,
          fontWeight: 500,
          lineHeight: 1.35,
          padding: '6px 8px',
          maxWidth: 280,
          boxShadow: '0 4px 12px rgba(15, 23, 42, 0.18)',
          ...tabularNumeric,
        },
        arrow: {
          color: graphite[900],
        },
      },
    },
    MuiDrawer: {
      styleOverrides: {
        paper: {
          borderRadius: 0,
          borderRight: `1px solid ${borderSubtle}`,
          backgroundColor: '#ffffff',
          backgroundImage: 'none',
          color: graphite[900],
        },
        paperAnchorLeft: {
          borderRight: `1px solid ${borderSubtle}`,
        },
        paperAnchorRight: {
          borderLeft: `1px solid ${borderSubtle}`,
        },
      },
    },
    MuiAppBar: {
      defaultProps: {
        elevation: 0,
        color: 'inherit',
      },
      styleOverrides: {
        root: {
          backgroundImage: 'none',
          backgroundColor: '#ffffff',
          color: graphite[900],
          borderBottom: `1px solid ${borderSubtle}`,
          boxShadow: 'none',
        },
        colorPrimary: {
          backgroundColor: '#ffffff',
          color: graphite[900],
        },
        colorInherit: {
          backgroundColor: '#ffffff',
          color: graphite[900],
        },
      },
    },
    MuiToolbar: {
      styleOverrides: {
        root: {
          minHeight: 48,
          '@media (min-width: 600px)': {
            minHeight: 48,
          },
        },
        dense: {
          minHeight: 40,
        },
      },
    },
    MuiListItemButton: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          minHeight: 36,
          '&.Mui-selected': {
            backgroundColor: cobalt.light,
            color: cobalt.dark,
            '&:hover': {
              backgroundColor: 'rgba(29, 78, 216, 0.14)',
            },
            '& .MuiListItemIcon-root': {
              color: cobalt.main,
            },
          },
          '&:hover': {
            backgroundColor: 'rgba(15, 23, 42, 0.04)',
          },
          '&.Mui-focusVisible': {
            boxShadow: focusRing,
          },
        },
      },
    },
    MuiListItemIcon: {
      styleOverrides: {
        root: {
          minWidth: 34,
          color: graphite[600],
        },
      },
    },
    MuiDivider: {
      styleOverrides: {
        root: {
          borderColor: borderSubtle,
        },
      },
    },
    MuiLink: {
      styleOverrides: {
        root: {
          color: cobalt.main,
          textDecorationColor: 'rgba(29, 78, 216, 0.35)',
          '&:hover': {
            color: cobalt.dark,
          },
        },
      },
    },
    MuiSkeleton: {
      styleOverrides: {
        root: {
          backgroundColor: graphite[100],
        },
      },
    },
    MuiLinearProgress: {
      styleOverrides: {
        root: {
          borderRadius: 4,
          height: 6,
          backgroundColor: graphite[100],
        },
        bar: {
          borderRadius: 4,
        },
      },
    },
    MuiSwitch: {
      styleOverrides: {
        root: {
          width: 40,
          height: 24,
          padding: 0,
        },
        switchBase: {
          padding: 3,
          '&.Mui-checked': {
            transform: 'translateX(16px)',
            color: '#ffffff',
            '& + .MuiSwitch-track': {
              backgroundColor: cobalt.main,
              opacity: 1,
              border: 0,
            },
          },
          '&.Mui-disabled': {
            color: graphite[100],
            '& + .MuiSwitch-track': {
              opacity: 0.5,
            },
          },
        },
        thumb: {
          width: 18,
          height: 18,
          boxShadow: '0 1px 2px rgba(15, 23, 42, 0.2)',
        },
        track: {
          borderRadius: 12,
          backgroundColor: graphite[300],
          opacity: 1,
        },
      },
    },
    MuiCheckbox: {
      styleOverrides: {
        root: {
          borderRadius: 4,
          padding: 6,
          color: graphite[400],
          '&.Mui-checked, &.MuiCheckbox-indeterminate': {
            color: cobalt.main,
          },
          '&.Mui-disabled': {
            color: graphite[300],
          },
        },
      },
    },
    MuiRadio: {
      styleOverrides: {
        root: {
          padding: 6,
          color: graphite[400],
          '&.Mui-checked': {
            color: cobalt.main,
          },
          '&.Mui-disabled': {
            color: graphite[300],
          },
        },
      },
    },
    MuiPaginationItem: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          fontSize: 13,
          minWidth: 30,
          height: 30,
          ...tabularNumeric,
          '&.Mui-selected': {
            backgroundColor: cobalt.main,
            color: '#ffffff',
            '&:hover': {
              backgroundColor: cobalt.dark,
            },
          },
        },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: {
          borderRadius: 6,
          border: `1px solid ${borderSubtle}`,
          boxShadow: '0 12px 40px rgba(15, 23, 42, 0.14)',
          backgroundImage: 'none',
        },
      },
    },
    MuiDialogTitle: {
      styleOverrides: {
        root: {
          fontSize: 16,
          fontWeight: 700,
          color: graphite[900],
          padding: '16px 20px 8px',
        },
      },
    },
    MuiDialogContent: {
      styleOverrides: {
        root: {
          padding: '8px 20px 16px',
        },
      },
    },
    MuiDialogActions: {
      styleOverrides: {
        root: {
          padding: '8px 16px 16px',
          gap: 8,
        },
      },
    },
    MuiBadge: {
      styleOverrides: {
        badge: {
          ...tabularNumeric,
          fontWeight: 700,
          fontSize: 11,
          minWidth: 18,
          height: 18,
          borderRadius: 9,
        },
      },
    },
  },
});

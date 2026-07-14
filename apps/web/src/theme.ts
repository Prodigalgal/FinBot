import { createTheme } from '@mui/material/styles';

export const theme = createTheme({
  palette: {
    mode: 'light',
    background: {
      default: '#f5f7fa',
      paper: '#ffffff',
    },
    primary: {
      main: '#235789',
      dark: '#173d61',
      light: '#dbeaf7',
    },
    secondary: {
      main: '#2f7d5c',
    },
    success: {
      main: '#2e7d32',
    },
    warning: {
      main: '#b26a00',
    },
    error: {
      main: '#b42318',
    },
    text: {
      primary: '#17202a',
      secondary: '#5c6670',
    },
    divider: '#d7dde3',
  },
  shape: {
    borderRadius: 8,
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
    h1: { fontSize: 26, fontWeight: 700, lineHeight: 1.2 },
    h2: { fontSize: 20, fontWeight: 700, lineHeight: 1.25 },
    h3: { fontSize: 17, fontWeight: 700, lineHeight: 1.3 },
    subtitle1: { fontSize: 14, fontWeight: 600 },
    body1: { fontSize: 14, lineHeight: 1.55 },
    body2: { fontSize: 13, lineHeight: 1.45 },
    button: { fontSize: 13, fontWeight: 700, textTransform: 'none' },
    caption: { fontSize: 12, lineHeight: 1.35 },
  },
  components: {
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          minHeight: 34,
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          borderRadius: 8,
          boxShadow: '0 1px 2px rgba(15, 23, 42, 0.06)',
          border: '1px solid #dfe5eb',
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          height: 24,
          fontWeight: 600,
        },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: {
          minHeight: 38,
          fontSize: 13,
          fontWeight: 700,
          textTransform: 'none',
        },
      },
    },
    MuiTextField: {
      defaultProps: {
        size: 'small',
      },
    },
    MuiSelect: {
      defaultProps: {
        size: 'small',
      },
    },
  },
});

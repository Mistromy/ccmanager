package tui

import (
	"os"
	"strings"

	"charm.land/bubbles/v2/viewport"
	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"
	"charm.land/log/v2"
)

var (
	paneStyle = func() lipgloss.Style {
		b := lipgloss.RoundedBorder()
		return lipgloss.NewStyle().BorderStyle(b)
	}()
)

type logMsg struct{ string }
type logWriter struct{ *tea.Program }

func (l logWriter) Write(bytes []byte) (int, error) {
	message := string(bytes)
	strings.TrimRight(message, "\r\n")
	l.Send(logMsg{message})
	return len(bytes), nil
}

type model struct {
	content  []string
	ready    bool
	viewport viewport.Model
}

func (m model) Init() tea.Cmd {
	return nil
}

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	var (
		cmd  tea.Cmd
		cmds []tea.Cmd
	)

	switch msg := msg.(type) {
	case tea.KeyPressMsg:
		if k := msg.String(); k == "ctrl+c" {
			return m, tea.Quit
		}

	case logMsg:
		m.content = append(m.content, msg.string)
		if m.ready {
			//setContent
		}

	case tea.WindowSizeMsg:
		if !m.ready {
			m.viewport = viewport.New(viewport.WithWidth(msg.Width-2), viewport.WithHeight(msg.Height-2))
			m.viewport.SetContent(strings.Join(m.content, "\n"))
			m.ready = true
		} else {
			m.viewport.SetWidth(msg.Width - 2)
			m.viewport.SetHeight(msg.Height - 2)
		}
	}
	m.viewport, cmd = m.viewport.Update(msg)
	cmds = append(cmds, cmd)
	return m, tea.Batch(cmds...)
}

func (m model) View() tea.View {
	var v tea.View
	v.AltScreen = true
	v.MouseMode = tea.MouseModeCellMotion
	if !m.ready {
		v.SetContent("\n  Initializing...")
	} else {
		v.SetContent(paneStyle.Render(m.viewport.View()))
	}
	return v
}

func Run() {
	p := tea.NewProgram(
		model{},
	)
	log.SetOutput(logWriter{p})
	if _, err := p.Run(); err != nil {
		log.SetOutput(os.Stderr)
		log.Info("Closing Tui...")
	}
}

/*
 * Runs an AVR program (Intel HEX) in simavr and copies what it sends on the UART to stdout,
 * byte for byte. Used by scripts/check-boards.sh to compare bare-metal AVR code with the
 * interpreter. Stops when the program sleeps with interrupts off (EVIA's rt_end), crashes,
 * or after a time limit.
 *
 *   evia_sim MCU program.hex [options]
 *     --seconds N      stop after N seconds of simulated time (default 120)
 *     --watch D5       report every change of output pin PD5 on stderr: "[1000 ms] PD5 1"
 *     --button D4      a push button to ground on PD4, pressed during the second half of every
 *                      2-second period - the same button the interpreter's simulated board has
 *     --adc 7          feed ADC channel 7 with the interpreter's analog signal: 0..1023..0 over 4 s
 *     --uart-loop      wire the UART's TX to its RX (what the interpreter's serial port does)
 *     --input FILE     type FILE into the first UART, a byte every millisecond (a program's Input)
 *     --boot BOOT.hex  a bootloader too (at its own address): the chip starts in it, as after a reset
 *                      by the reset pin (program.hex may be "-": an empty flash)
 *     --pty            the first UART on a pseudo-terminal (its name goes to stderr), in real time and
 *                      starting when the first byte arrives: an uploader can talk to the bootloader
 *     --i2c 80         an I2C device at 7-bit address 80 with 256 registers, all 0 at the start - the
 *                      device the interpreter's simulated board has (SPI needs nothing: the chip's own
 *                      model already returns the byte it sent)
 *
 * Built against simavr's simulator core (without its ELF loader, so libelf isn't needed).
 */
#define _XOPEN_SOURCE 600
#define _DEFAULT_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>
#include "sim_avr.h"
#include "sim_hex.h"
#include "avr_uart.h"
#include "avr_ioport.h"
#include "sim_cycle_timers.h"
#include "avr_adc.h"
#include "avr_twi.h"
#include "avr_watchdog.h"

/* sim_hex.c can also load ELF files; evia_sim only loads HEX, so these are never called. */
struct elf_firmware_t;
void sim_default_mcu(struct elf_firmware_t *firmware, const char *file) {
    (void) firmware;
    (void) file;
}
int elf_read_firmware(const char *file, struct elf_firmware_t *firmware) {
    (void) file;
    (void) firmware;
    return -1;
}

static avr_t *avr;
static avr_irq_t *uart_in[2];
static int uart_loop;

static int pty = -1;          /* --pty: the master side */
static int uart_xoff = 1;     /* the chip can't take a byte now: until it first polls its receiver, and while full */
static int pty_pending = -1;  /* a byte that came before the chip could take it */

static void uart_out(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void) irq;
    if (pty >= 0 && (intptr_t) param == 0) {
        uint8_t c = (uint8_t) value;
        if (write(pty, &c, 1) != 1) { /* nobody is listening: dropped, as on a wire */ }
        return;
    }
    putchar((int) (value & 0xFF));
    if (uart_loop && uart_in[(intptr_t) param]) avr_raise_irq(uart_in[(intptr_t) param], value & 0xFF);
}

/* ---- the I2C device: 256 registers; a write is  register, value  and a read continues from the register */
static uint8_t i2c_regs[256];
static uint8_t i2c_addr;        /* 8-bit form of the device address (7-bit address << 1) */
static int i2c_selected;
static int i2c_index;
static uint8_t i2c_reg;
static avr_irq_t *i2c_irq;      /* two IRQs: [TWI_IRQ_INPUT] to the chip, [TWI_IRQ_OUTPUT] from the chip */

static void i2c_hook(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void) irq;
    (void) param;
    avr_twi_msg_irq_t v;
    v.u.v = value;
    if (v.u.twi.msg & TWI_COND_STOP) {
        i2c_selected = 0;
        i2c_index = 0;
    }
    if (v.u.twi.msg & TWI_COND_START) {
        i2c_selected = 0;
        i2c_index = 0;
        if ((v.u.twi.addr & ~1) == i2c_addr) {
            i2c_selected = v.u.twi.addr;
            avr_raise_irq(i2c_irq + TWI_IRQ_INPUT, avr_twi_irq_msg(TWI_COND_ACK, i2c_selected, 1));
        }
    }
    if (i2c_selected) {
        if (v.u.twi.msg & TWI_COND_WRITE) {
            avr_raise_irq(i2c_irq + TWI_IRQ_INPUT, avr_twi_irq_msg(TWI_COND_ACK, i2c_selected, 1));
            if (i2c_index == 0) i2c_reg = v.u.twi.data;
            else i2c_regs[i2c_reg++] = v.u.twi.data;
            i2c_index++;
        }
        if (v.u.twi.msg & TWI_COND_READ) {
            avr_raise_irq(i2c_irq + TWI_IRQ_INPUT, avr_twi_irq_msg(TWI_COND_READ, i2c_selected, i2c_regs[i2c_reg++]));
        }
    }
}

static void i2c_attach(int addr7) {
    static const char *names[2] = { [TWI_IRQ_INPUT] = "8>device.out", [TWI_IRQ_OUTPUT] = "32<device.in" };
    i2c_addr = (uint8_t) (addr7 << 1);
    i2c_irq = avr_alloc_irq(&avr->irq_pool, 0, 2, names);
    avr_irq_register_notify(i2c_irq + TWI_IRQ_OUTPUT, i2c_hook, NULL);
    avr_connect_irq(i2c_irq + TWI_IRQ_INPUT, avr_io_getirq(avr, AVR_IOCTL_TWI_GETIRQ(0), TWI_IRQ_INPUT));
    avr_connect_irq(avr_io_getirq(avr, AVR_IOCTL_TWI_GETIRQ(0), TWI_IRQ_OUTPUT), i2c_irq + TWI_IRQ_OUTPUT);
}

static unsigned long now_ms(void) {
    return (unsigned long) (avr->cycle / (avr->frequency / 1000));
}

static void pin_changed(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void) irq;
    fprintf(stderr, "[%lu ms] P%s %u\n", now_ms(), (const char *) param, value & 1);
}

/* The button: released (pin pulled high) except during the second half of each 2-second period. */
static avr_irq_t *button;
static char button_port;
static int button_bit;
static int button_down = -1;

static avr_cycle_count_t button_tick(struct avr_t *a, avr_cycle_count_t when, void *param) {
    (void) a;
    (void) param;
    int down = now_ms() % 2000 >= 1000;
    if (down != button_down) {
        button_down = down;
        /* a pressed button holds the pin low, as an external pull: otherwise a write to its port (the LED on
           another pin of it) would put the pin's pull-up level back */
        avr_ioport_external_t e = {.name = button_port, .mask = down ? 1 << button_bit : 0, .value = 0};
        avr_ioctl(avr, AVR_IOCTL_IOPORT_SET_EXTERNAL(button_port), &e);
        avr_raise_irq(button, down ? 0 : 1);
    }
    return when + avr->frequency / 1000;   /* again in 1 ms */
}

/* The analog signal: a triangle wave 0..1023..0 with a 4.096 s period, as millivolts (5 V reference). */
static avr_irq_t *adc;

static avr_cycle_count_t adc_tick(struct avr_t *a, avr_cycle_count_t when, void *param) {
    (void) a;
    (void) param;
    unsigned long t = now_ms() % 4096;
    unsigned long value = t < 2048 ? t / 2 : (4095 - t) / 2;
    avr_raise_irq(adc, (uint32_t) ((value * 5000 + 1022) / 1023));
    return when + avr->frequency / 1000;
}

static void uart_xon(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void) irq; (void) value; (void) param;
    uart_xoff = 0;
}

static void uart_xoff_on(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void) irq; (void) value; (void) param;
    uart_xoff = 1;
}

/* --pty: a byte from the uploader into UART 0 when the chip can take it, at most one per byte time */
static avr_cycle_count_t pty_tick(struct avr_t *a, avr_cycle_count_t when, void *param) {
    (void) a;
    (void) param;
    uint8_t c;
    if (!uart_xoff) {
        if (pty_pending >= 0) {
            avr_raise_irq(uart_in[0], (uint32_t) pty_pending);
            pty_pending = -1;
        } else if (read(pty, &c, 1) == 1) {
            avr_raise_irq(uart_in[0], c);
        }
    }
    return when + avr->frequency / 11520;   /* 10 bits at 115200 baud */
}

/* The ATmega8, 16 and 32 share one address between UBRRH and UCSRC; a write with URSEL (bit 7) set is UCSRC.
   simavr takes every write as UBRRH, so a program that sets the frame format (Optiboot does) gets a baud rate of
   about 1300. This puts the divisor's high byte back and lets the UART work out the rate again. */
#define UBRRH_UCSRC 0x40
#define UBRRL_ADDR 0x29
static uint8_t ubrrh_value;

static void ursel_write(struct avr_t *a, avr_io_addr_t addr, uint8_t v, void *param) {
    (void) param;
    if (!(v & 0x80)) {
        ubrrh_value = v;
        return;
    }
    a->data[addr] = ubrrh_value;
    avr_io_addr_t l = AVR_DATA_TO_IO(UBRRL_ADDR);
    if (a->io[l].w.c) a->io[l].w.c(a, UBRRL_ADDR, a->data[UBRRL_ADDR], a->io[l].w.param);
}

static long long wall_us(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long long) tv.tv_sec * 1000000 + tv.tv_usec;
}

/* ---- --input: the file's bytes into UART 0, one a millisecond (slower than the chip reads them) */
static FILE *input;

static avr_cycle_count_t input_tick(struct avr_t *a, avr_cycle_count_t when, void *param) {
    (void) a;
    (void) param;
    int c = fgetc(input);
    if (c == EOF) return 0;
    avr_raise_irq(uart_in[0], (uint32_t) c);
    return when + avr->frequency / 1000;
}

static avr_irq_t *pin_irq(const char *name) {
    if (strlen(name) != 2 || name[1] < '0' || name[1] > '7') {
        fprintf(stderr, "evia_sim: pin names look like D4 or B5, not %s\n", name);
        exit(2);
    }
    avr_irq_t *irq = avr_io_getirq(avr, AVR_IOCTL_IOPORT_GETIRQ(name[0]), name[1] - '0');
    if (!irq) {
        fprintf(stderr, "evia_sim: this chip has no port %c\n", name[0]);
        exit(2);
    }
    return irq;
}

static void quiet_log(avr_t *avr, const int level, const char *format, va_list ap) {
    (void) avr;
    if (level <= LOG_ERROR) vfprintf(stderr, format, ap);
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: evia_sim MCU program.hex [--seconds N] [--watch D5] [--button D4] [--adc 0] [--uart-loop] [--i2c 80] [--input FILE]\n");
        return 2;
    }
    double seconds = 120;
    avr = avr_make_mcu_by_name(argv[1]);
    if (!avr) {
        fprintf(stderr, "evia_sim: unknown MCU %s\n", argv[1]);
        return 2;
    }
    avr_global_logger_set(quiet_log);
    avr_init(avr);
    if (!strcmp(argv[1], "atmega8") || !strcmp(argv[1], "atmega16") || !strcmp(argv[1], "atmega32")) {
        avr_register_io_write(avr, UBRRH_UCSRC, ursel_write, NULL);
    }
    /* A watchdog without an interrupt (ATmega8, 16, 32) leaves its interrupt's IRQs uninitialised, and simavr's
       watchdog then crashes at the second reset (a bootloader's watchdog reset): initialise them. */
    for (avr_io_t *io = avr->io_port; io; io = io->next) {
        if (strcmp(io->kind, "watchdog") != 0) continue;
        avr_watchdog_t *wd = (avr_watchdog_t *) io;
        static const char *names[AVR_INT_IRQ_COUNT] = {">wdt.pending", ">wdt.running"};
        if (!wd->watchdog.vector && !wd->watchdog.irq[0].pool) avr_init_irq(&avr->irq_pool, wd->watchdog.irq, 0, AVR_INT_IRQ_COUNT, names);
    }
    avr->frequency = 16000000;
    avr->vcc = avr->avcc = 5000;   /* millivolts: a 5 V board */

    uint32_t size = 0, start = 0;
    if (strcmp(argv[2], "-") != 0) {
        uint8_t *code = read_ihex_file(argv[2], &size, &start);
        if (!code) {
            fprintf(stderr, "evia_sim: can't read %s\n", argv[2]);
            return 2;
        }
        avr_loadcode(avr, code, size, start);
        free(code);
    }
    avr->pc = 0;
    for (int i = 3; i + 1 < argc; i++) {
        if (strcmp(argv[i], "--boot") != 0) continue;
        uint8_t *boot = read_ihex_file(argv[i + 1], &size, &start);
        if (!boot) {
            fprintf(stderr, "evia_sim: can't read %s\n", argv[i + 1]);
            return 2;
        }
        avr_loadcode(avr, boot, size, start);
        free(boot);
        avr->reset_pc = start;     /* BOOTRST: resets go to the bootloader */
        avr->pc = start;
        avr_regbit_clear(avr, avr->reset_flags.porf);
        avr_regbit_set(avr, avr->reset_flags.extrf);   /* as if the reset pin had been pulsed (DTR) */
    }

    const char names[] = {'0', '1'};
    for (unsigned i = 0; i < sizeof names; i++) {
        uint32_t flags = 0;
        if (avr_ioctl(avr, AVR_IOCTL_UART_GET_FLAGS(names[i]), &flags) < 0) continue;   /* no such UART */
        flags &= ~(AVR_UART_FLAG_STDIO | AVR_UART_FLAG_POLL_SLEEP);   /* no stdio; no usleep on every poll of the status */
        avr_ioctl(avr, AVR_IOCTL_UART_SET_FLAGS(names[i]), &flags);
        avr_irq_t *out = avr_io_getirq(avr, AVR_IOCTL_UART_GETIRQ(names[i]), UART_IRQ_OUTPUT);
        if (out) avr_irq_register_notify(out, uart_out, (void *) (intptr_t) i);
        uart_in[i] = avr_io_getirq(avr, AVR_IOCTL_UART_GETIRQ(names[i]), UART_IRQ_INPUT);
    }

    for (int i = 3; i < argc; i += 2) {
        if (!strcmp(argv[i], "--uart-loop")) {
            uart_loop = 1;
            i--;
            continue;
        }
        if (!strcmp(argv[i], "--pty")) {
            pty = posix_openpt(O_RDWR | O_NOCTTY);
            if (pty < 0 || grantpt(pty) || unlockpt(pty) || !uart_in[0]) {
                fprintf(stderr, "evia_sim: no pseudo-terminal\n");
                return 2;
            }
            fcntl(pty, F_SETFL, O_NONBLOCK);
            fprintf(stderr, "evia_sim: pty %s\n", ptsname(pty));
            avr_irq_register_notify(avr_io_getirq(avr, AVR_IOCTL_UART_GETIRQ('0'), UART_IRQ_OUT_XON), uart_xon, NULL);
            avr_irq_register_notify(avr_io_getirq(avr, AVR_IOCTL_UART_GETIRQ('0'), UART_IRQ_OUT_XOFF), uart_xoff_on, NULL);
            i--;
            continue;
        }
        if (!strcmp(argv[i], "--boot")) continue;   /* loaded above */
        if (i + 1 >= argc) {
            fprintf(stderr, "evia_sim: option %s needs a value\n", argv[i]);
            return 2;
        }
        if (!strcmp(argv[i], "--i2c")) {
            i2c_attach(atoi(argv[i + 1]));
        } else if (!strcmp(argv[i], "--input")) {
            input = fopen(argv[i + 1], "rb");
            if (!input || !uart_in[0]) {
                fprintf(stderr, "evia_sim: can't type %s into the UART\n", argv[i + 1]);
                return 2;
            }
            avr_cycle_timer_register(avr, avr->frequency / 100, input_tick, NULL);
        } else if (!strcmp(argv[i], "--seconds")) {
            seconds = atof(argv[i + 1]);
        } else if (!strcmp(argv[i], "--watch")) {
            avr_irq_register_notify(pin_irq(argv[i + 1]), pin_changed, argv[i + 1]);
        } else if (!strcmp(argv[i], "--button")) {
            button = pin_irq(argv[i + 1]);
            button_port = argv[i + 1][0];
            button_bit = argv[i + 1][1] - '0';
            button_tick(avr, avr->cycle, NULL);
            avr_cycle_timer_register(avr, avr->frequency / 1000, button_tick, NULL);
        } else if (!strcmp(argv[i], "--adc")) {
            adc = avr_io_getirq(avr, AVR_IOCTL_ADC_GETIRQ, ADC_IRQ_ADC0 + atoi(argv[i + 1]));
            adc_tick(avr, avr->cycle, NULL);
            avr_cycle_timer_register(avr, avr->frequency / 1000, adc_tick, NULL);
        } else {
            fprintf(stderr, "evia_sim: unknown option %s\n", argv[i]);
            return 2;
        }
    }

    avr_cycle_count_t limit = (avr_cycle_count_t) (seconds * avr->frequency);
    long long t0 = 0;
    if (pty >= 0) {             /* wait for the uploader, then keep to real time */
        fflush(stderr);
        uint8_t c;
        for (;;) {
            ssize_t n = read(pty, &c, 1);
            if (n == 1) break;
            usleep(1000);
        }
        pty_pending = c;
        avr_cycle_timer_register(avr, avr->frequency / 11520, pty_tick, NULL);
        t0 = wall_us();
    }
    int state = cpu_Running;
    avr_cycle_count_t paced = 0;
    long long before_us = 0;      /* simulated time before the last reset (a reset sets the cycle count to 0) */
    for (;;) {
        state = avr_run(avr);
        if (state == cpu_Done && pty >= 0 && avr->reset_pc && avr->pc >= avr->reset_pc) {
            /* the bootloader waits for its watchdog to reset the chip (rjmp .-2, interrupts off): reset it */
            before_us += (long long) (avr->cycle / (avr->frequency / 1000000));
            avr_regbit_set(avr, avr->reset_flags.wdrf);
            avr_reset(avr);
            avr_cycle_timer_register(avr, avr->frequency / 11520, pty_tick, NULL);
            paced = 0;
            state = cpu_Running;
            continue;
        }
        if (state == cpu_Done || state == cpu_Crashed) break;
        if (pty >= 0 && avr->cycle - paced > 16000) {
            paced = avr->cycle;
            long long ahead = before_us + (long long) (avr->cycle / (avr->frequency / 1000000)) - (wall_us() - t0);
            if (ahead > 2000) usleep((useconds_t) ahead);
        }
        if (avr->cycle > limit) {
            fflush(stdout);
            fprintf(stderr, "evia_sim: still running after %.0f s of simulated time\n", seconds);
            return 3;
        }
    }
    fflush(stdout);
    if (getenv("EVIA_SIM_DEBUG")) fprintf(stderr, "evia_sim: stopped, state %d, pc %05x\n", state, avr->pc);
    return state == cpu_Crashed ? 4 : 0;
}

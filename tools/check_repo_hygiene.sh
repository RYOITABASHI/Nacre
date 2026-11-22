#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

# Scope: this check guards against accidentally committing real values such as
# IP addresses, passwords, and signing files. Deliberate obfuscation can evade it.

# Synthetic fixtures for the sanitizeToken unit test.
TOKEN_FORMAT_ALLOW_FILE='ime-core/src/test/kotlin/space/manus/nacre/ime/input/ShellyBridgeContextTest.kt'
# Add intentionally published contact addresses as quoted array entries.
ALLOW_EMAILS=()
# Alternate exact relative paths and anchored regular expressions for whole lines.
CREDENTIAL_LINE_ALLOWLIST=()

if (( $# > 1 )); then
    printf 'Usage: tools/check_repo_hygiene.sh [ROOT]\n' >&2
    exit 2
fi

checker_source=${BASH_SOURCE[0]}
if [[ $checker_source != /* ]]; then checker_source=$PWD/$checker_source; fi

if ! cd -- "${1:-$(dirname -- "${BASH_SOURCE[0]}")/..}" 2>/dev/null; then
    printf 'Cannot open scan root.\n' >&2
    exit 2
fi

scan_root=$(pwd -P)

private_ip='(?<![\d.])(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})(?![\d]|\.\d)'
local_path='/home/[^/[:space:]]+/|/Users/[^/[:space:]]+/|C:\\Users\\[^\\/[:space:]]+|/data/data/com\.termux/files/home/[^/[:space:]]+/'
token_format='\b(?:sk-[A-Za-z0-9_-]{16,}|(?:ghp_|github_pat_|AIza)[A-Za-z0-9_-]{20,}|AKIA[A-Z0-9]{16}|xox[baprs]-[A-Za-z0-9-]{10,})|BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY'
email='(?<![A-Za-z0-9_.%+-])[A-Za-z0-9_%+-]+(?:\.[A-Za-z0-9_%+-]+)*@(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}\b'
phone='(?<![0-9-])0[0-9]+-[0-9]+-[0-9]{4}(?![0-9-])'
credential_keyword='[Pp][Aa][Ss][Ss]([Ww][Oo][Rr][Dd]|[Ww][Dd])|[Tt][Oo][Kk][Ee][Nn]|[Ss][Ee][Cc][Rr][Ee][Tt]|[Aa][Pp][Ii]_?[Kk][Ee][Yy]|[Aa][Cc][Cc][Ee][Ss][Ss]_[Kk][Ee][Yy]|[Pp][Rr][Ii][Vv][Aa][Tt][Ee]_[Kk][Ee][Yy]|[Cc][Rr][Ee][Dd][Ee][Nn][Tt][Ii][Aa][Ll]'
assignment_target='^([A-Za-z_][A-Za-z0-9_]*)(\[[^]]*\])?'
parameter_word_prefix='^([A-Za-z_][A-Za-z0-9_]*|[0-9]+|[?#@*!$-])(\[[^]]*\])?:?[-=+?]'
fd_prefix='([0-9]+|\{[A-Za-z_][A-Za-z0-9_]*\})'

report() {
    printf '%s:%s:%s\n' "${path#./}" "$1" "$2"
    violations=$((violations + 1))
}

scan() {
    local category=$1 pattern=$2 matches status line value allowed previous_line=''
    if matches=$(grep -InPo -- "$pattern" "$path" 2>/dev/null); then
        while IFS=: read -r line value; do
            [[ $line != "$previous_line" ]] || continue
            if [[ $category == email ]]; then
                for allowed in "${ALLOW_EMAILS[@]}"; do
                    [[ $value != "$allowed" ]] || continue 2
                done
            fi
            report "$line" "$category"
            previous_line=$line
        done <<< "$matches"
    else
        status=$?
        if (( status != 1 )); then
            printf 'Could not scan a file.\n' >&2
            exit 2
        fi
    fi
}

# These helpers share the RHS cursor. Each nested expansion consumes its
# own closing delimiter before the enclosing parser resumes.
consume_expansion() {
    local variable='^\$([A-Za-z_][A-Za-z0-9_]*|[0-9?#@*!$-])'
    case ${rhs:cursor:2} in
        '${')
            cursor=$((cursor + 2))
            scan_parameter_expansion
            ;;
        '$(')
            cursor=$((cursor + 2))
            scan_command_substitution
            ;;
        *)
            [[ ${rhs:cursor} =~ $variable ]] || return 1
            cursor=$((cursor + ${#BASH_REMATCH[0]}))
            ;;
    esac
}

scan_parameter_expansion() {
    local parameter='^[#!]?([A-Za-z_][A-Za-z0-9_]*|[0-9]+|[?#@*!$-])(\[([@*]|[0-9]+)\])?'
    local pattern_char='^[][0-9+?:=#%/!^~,*@.&|<>()-]$'
    local arithmetic_char='^[0-9[:space:]+*/%()-]$'
    local identifier='^[A-Za-z_][A-Za-z0-9_]*'
    local mode=pattern char quote='' length_separator=0 expansion_start
    local substitution=0 in_brackets=0
    local character_class='^\[:[a-z]+:\]'
    [[ ${rhs:cursor} =~ $parameter ]] || return 1
    local parameter_name=${BASH_REMATCH[1]} parameter_spelling=${BASH_REMATCH[0]}
    cursor=$((cursor + ${#parameter_spelling}))
    if [[ $parameter_spelling == "$parameter_name" && $parameter_name =~ ^([0-9]+|[#*@])$ &&
            ${rhs:cursor} == @(':-'*|'-'*) ]]; then
        local current
        credential_special_parameter_fact "$parameter_name" 1
        if [[ $current == scalar:* && ( $current != scalar: || ${rhs:cursor:1} == '-' ) ]]; then
            # The argument supplies the value. Parse the unused word solely
            # to advance the classifier to the matching closing delimiter.
            local source=$rhs position=$cursor
            read_shell_word '}' 0 0 0 0 0 0 0 0 0 1
            [[ ${source:position-1:1} == '}' ]] || return 1
            cursor=$position
            return 0
        fi
    fi
    case ${rhs:cursor} in
        ':-'*|':='*|':+'*) mode=value; cursor=$((cursor + 2)) ;;
        '-'*|'='*|'+'*) mode=value; cursor=$((cursor + 1)) ;;
        ':?'*) mode=message; cursor=$((cursor + 2)) ;;
        '?'*) mode=message; cursor=$((cursor + 1)) ;;
        :[A-Za-z_0-9[:space:]\(]*) mode=substring; cursor=$((cursor + 1)) ;;
        '//'*) substitution=1; cursor=$((cursor + 2)) ;;
        '/'*) substitution=1; cursor=$((cursor + 1)) ;;
        '%%'*|'##'*) mode=trim_pattern; cursor=$((cursor + 2)) ;;
        '%'*|'#'*) mode=trim_pattern; cursor=$((cursor + 1)) ;;
    esac

    while (( cursor < ${#rhs} )); do
        char=${rhs:cursor:1}
        if [[ $char == '}' && -z $quote ]]; then
            cursor=$((cursor + 1))
            return 0
        fi
        if (( substitution )) && [[ $mode == pattern ]]; then
            # The match pattern cannot contribute literal replacement text.
            if [[ $char == '\' && $quote != "'" ]]; then
                cursor=$((cursor + 2))
                continue
            elif [[ $quote != "'" && ( $char == '`' ||
                    ( $char == '$' && ${rhs:cursor+1:1} == ['{'\(] ) ) ]]; then
                skip_replacement_pattern_expansion
                continue
            elif [[ -n $quote || $char == "'" || $char == '"' ]]; then
                if [[ $char == "$quote" ]]; then
                    quote=''
                elif [[ -z $quote ]]; then
                    quote=$char
                fi
                cursor=$((cursor + 1))
                continue
            fi
        fi
        if [[ $char == '$' && $quote != "'" ]] &&
                { (( ! substitution )) || [[ $mode != pattern ]]; }; then
            expansion_start=$cursor
            consume_expansion && continue
            [[ $mode == message && $cursor == "$expansion_start" ]] || return 1
        fi
        case $mode in
            value)
                if (( substitution && ${patsub_replacement:-1} )) && [[ $char == '&' ]]; then
                    # With patsub_replacement enabled, & inserts the match.
                    cursor=$((cursor + 1))
                    continue
                fi
                [[ $char == '"' ]] && scan_expansion_quote || return 1
                continue
                ;;
            message|trim_pattern)
                if [[ $char == '\' && $quote != "'" ]]; then
                    cursor=$((cursor + 2))
                    continue
                elif [[ -n $quote ]]; then
                    if [[ $char == "$quote" ]]; then quote=''; fi
                elif [[ $char == '"' || $char == "'" ]]; then
                    quote=$char
                fi
                ;;
            substring)
                if [[ $char == ':' && $length_separator == 0 ]]; then
                    length_separator=1
                elif [[ ${rhs:cursor} =~ $identifier ]]; then
                    cursor=$((cursor + ${#BASH_REMATCH[0]}))
                    continue
                else
                    [[ $char =~ $arithmetic_char ]] || return 1
                fi
                ;;
            pattern)
                if (( in_brackets )) && [[ ${rhs:cursor} =~ $character_class ]]; then
                    cursor=$((cursor + ${#BASH_REMATCH[0]}))
                    continue
                fi
                case $char in
                    '[')
                        if (( ! in_brackets )); then
                            in_brackets=1
                            cursor=$((cursor + 1))
                            # A leading ] is a member of the bracket expression.
                            case ${rhs:cursor:1} in
                                '!'|'^') cursor=$((cursor + 1)) ;;
                            esac
                            if [[ ${rhs:cursor:1} == ']' ]]; then
                                cursor=$((cursor + 1))
                            fi
                            continue
                        fi
                        ;;
                    ']') in_brackets=0 ;;
                esac
                if (( substitution )); then
                    if [[ $char == '/' ]] && (( ! in_brackets )); then mode=value; fi
                    cursor=$((cursor + 1))
                    continue
                fi
                [[ $char =~ $pattern_char ]] || return 1
                ;;
        esac
        cursor=$((cursor + 1))
    done
    return 1
}

skip_replacement_pattern_expansion() {
    local source=$rhs position=$((cursor + 2)) closing='}' word_expansion=0
    if [[ ${rhs:cursor:1} == '`' ]]; then
        position=$((cursor + 1))
        closing='`'
    elif [[ ${rhs:cursor+1:1} == '(' ]]; then
        closing=')'
    fi
    read_shell_word "$closing"
    cursor=$position
}

quoted_segment_allowed() {
    [[ $1 =~ ^[[:space:]:._-]*$ ]]
}

# Append literal text to the current word. For expansion-only quotes, check
# each run of literal characters before moving past the next expansion.
# Here-strings defer the final segment check until the whole word is consumed.
scan_double_quoted() {
    local check_segments=${1:-0} char escaped
    local path_continuation='^(/[A-Za-z0-9._-]{1,12})+$'
    cursor=$((cursor + 1))
    while (( cursor < ${#rhs} )); do
        char=${rhs:cursor:1}
        case $char in
            '"')
                if (( check_segments == 1 )); then
                    quoted_segment_allowed "$segment" ||
                        [[ $check_segments == 1 && $segment =~ $path_continuation ]] || return 1
                fi
                cursor=$((cursor + 1))
                return 0
                ;;
            '$')
                if (( check_segments )); then
                    quoted_segment_allowed "$segment" ||
                        [[ $check_segments == 1 && $segment =~ $path_continuation ]] || return 1
                    segment=''
                fi
                consume_expansion || return 1
                has_expansion=1
                continue
                ;;
            '`') return 1 ;;
            '\')
                escaped=${rhs:cursor+1:1}
                case $escaped in
                    '$'|'`'|'"'|'\') char=$escaped; cursor=$((cursor + 1)) ;;
                esac
                ;;
        esac
        literal+=$char
        segment+=$char
        cursor=$((cursor + 1))
    done
    return 1
}

scan_expansion_quote() {
    local literal='' has_expansion=0 segment=''
    scan_double_quoted 1 && [[ -z $literal || $has_expansion == 1 ]]
}

validate_extraction_operand() {
    local rhs=$1 cursor=0 source=$1 position=0 literal='' word_expansion=0
    read_shell_word '' 1
}

# Options with arguments end a short-option cluster. Any remaining
# characters in that cluster are the argument.
consume_short_prefix_options() {
    local options=${1:1} char
    while [[ -n $options ]]; do
        char=${options:0:1}
        options=${options:1}
        case $command_prefix:$char in
            env:0) env_null=1 ;;
            env:[iv]|timeout:v) ;;
            env:u)
                if [[ -z $options ]]; then prefix_operand=3; else validate_env_unset "$options"; fi
                return 0
                ;;
            env:[Ca]|timeout:[ks])
                if [[ -z $options ]]; then prefix_operand=1; fi
                return 0
                ;;
            env:S)
                if [[ -n $options ]]; then
                    prefix_split=$options
                else
                    prefix_operand=2
                fi
                return 0
                ;;
            *) return 1 ;;
        esac
    done
}

validate_env_unset() {
    if [[ $1 != *"$env_expansion_marker"* && ( -z $1 || $1 == *=* ) ]]; then env_rejected=1; fi
}

validate_env_signals() {
    local signals=$1 signal number
    local signal_name='^[A-Za-z][A-Za-z0-9+-]*$'
    [[ $signals != *"$env_expansion_marker"* ]] || return 0
    while [[ -n $signals ]]; do
        signal=${signals%%,*}
        if [[ $signals == *,* ]]; then signals=${signals#*,}; else signals=''; fi
        [[ -n $signal ]] || continue
        number=${signal##+(0)}
        if [[ ! $signal =~ $signal_name && ! $number =~ ^([1-9]|[1-5][0-9]|6[0-4])$ ]] ||
                ! builtin kill -l "$signal" >/dev/null 2>&1; then
            env_rejected=1
            return
        fi
    done
}

# Resolve GNU env long options before consuming any attached or separate operand.
consume_env_long_option() {
    local name=${1%%=*} option match=''
    local attached=0
    if [[ $1 == *=* ]]; then attached=1; fi
    for option in --ignore-environment --unset --chdir --null --file --argv0 \
            --split-string --block-signal --default-signal --ignore-signal \
            --list-signal-handling --debug --help --version; do
        if [[ $option == "$name" ]]; then
            match=$option
            break
        fi
        if [[ $option == "$name"* ]]; then
            [[ -z $match ]] || return 1
            match=$option
        fi
    done
    case $match in
        --split-string)
            if (( attached )); then prefix_split=${1#*=}; else prefix_operand=2; fi
            ;;
        --unset)
            if (( attached )); then validate_env_unset "${1#*=}"; else prefix_operand=3; fi
            ;;
        --chdir|--file|--argv0)
            if (( ! attached )); then prefix_operand=1; fi
            ;;
        --block-signal|--default-signal|--ignore-signal)
            if (( attached )); then validate_env_signals "${1#*=}"; fi
            ;;
        --ignore-environment|--null|--list-signal-handling|--debug|--help|--version)
            (( ! attached )) || return 1
            case $match in
                --null) env_null=1 ;;
                --help|--version) env_rejected=1 ;;
            esac
            ;;
        *) return 1 ;;
    esac
}

# Consume a command prefix or one of its options. Both scanners keep this
# state until the wrapped command starts, including across redirections.
consume_command_prefix() {
    local argument=$1 argument_expansion=${2:-0}
    local attached_env_option='^(-[i0v]*S|--[a-z-]+=)'
    local attached_timeout_option='^(-v*[ks]|--(kill-after|signal)=)'
    if (( prefix_operand )); then
        if (( prefix_operand == 2 )); then prefix_split=$argument; fi
        if (( prefix_operand == 3 && ! argument_expansion )); then validate_env_unset "$argument"; fi
        prefix_operand=0
        return 0
    fi
    if [[ $command_prefix == env && $argument == - ]] &&
            (( ! argument_expansion && ! env_operands_started )); then
        env_operands_started=1
        prefix_options=0
        return 0
    fi
    if (( prefix_options )) && { (( ! argument_expansion )) ||
            [[ $command_prefix == env && $argument =~ $attached_env_option ]] ||
            [[ $command_prefix == timeout && $argument =~ $attached_timeout_option ]]; }; then
        case $command_prefix:$argument in
            *:--)
                prefix_options=0
                return 0
                ;;
            command:-[pvV]*)
                if [[ $argument =~ ^-[pvV]+$ ]]; then
                    if [[ $argument == *[vV]* ]]; then command_query=1; fi
                    return 0
                fi
                ;;
            env:--?*) consume_env_long_option "$argument" && return 0 ;;
            timeout:--kill-after|timeout:--signal|\
            nice:-n|nice:--adjustment|exec:-a|exec:-[cl]a|exec:-[cl][cl]a)
                prefix_operand=1
                return 0
                ;;
            time:-p|exec:-[cl]|exec:-cl|exec:-lc|\
            timeout:--kill-after=*|timeout:--signal=*|timeout:--preserve-status|timeout:--foreground|timeout:--verbose|\
            nice:-n?*|nice:--adjustment=*)
                return 0
                ;;
            env:-[^-]*|timeout:-[^-]*)
                consume_short_prefix_options "$argument" && return 0
                ;;
            nice:-[0-9]*|nice:--[0-9]*)
                if [[ $argument =~ ^--?[0-9]+$ ]]; then return 0; fi
                ;;
        esac
    fi
    [[ $command_prefix != env ]] || return 1
    (( ! command_query )) || return 1
    if [[ $command_prefix == timeout ]]; then
        # The next word is the command name. Its own options are independent.
        command_prefix=timeout_duration
        prefix_options=0
        return 0
    fi
    (( ! argument_expansion )) || return 1
    case $argument in
        time|command|builtin|exec|env|nice|timeout) command_prefix=$argument ;;
        */env|*/nice|*/timeout) command_prefix=${argument##*/} ;;
        *) return 1 ;;
    esac
    prefix_options=1
}

scan_command_substitution() {
    local char literal segment has_expansion command='' word_index=0 separator='' echo_options=1
    local command_prefix='' previous_word='' redirection=0 consumed_redirect=0 word_start quote_mode
    local prefix_options=0 prefix_operand=0 command_query=0 prefix_split=''
    local env_null=0 env_rejected=0 env_operands_started=0
    local printf_options=1 printf_variable=0 printf_format=0
    local component_options=1 basename_suffix=0 basename_multiple=0 component_operands=0
    local stripped source position word_expansion
    local identifier='^[A-Za-z_][A-Za-z0-9_]*$'
    local assignment='^[A-Za-z_][A-Za-z0-9_]*='
    local directive='%(%|[-+ #0]*[0-9]*(\.[0-9]*)?[A-Za-z])'
    local escape='\\[abefnrtv\\]'
    local format_separator="([[:space:]:._-]|$escape)*"
    local format_pattern="^$format_separator($directive$format_separator)+$"
    local date_directive='%([-_0^#]*[0-9]*[EO]?[A-Za-z%]|:{1,3}z)'
    # Date formats can use slashes and spaces between directives.
    local date_separator='[-:/ _.T]'
    local date_format="^\+$date_separator*($date_directive$date_separator*)+$"
    local escapes_only="^($escape)+$"
    local separators_only='^[:._-]*$'
    local component_path='^[/.:_-]*$'
    local fd_target='^&([0-9]+-?|-)([[:space:]|&;<>()]|$)'
    local redirect_fd="^${fd_prefix}[<>]"
    while (( cursor < ${#rhs} )); do
        char=${rhs:cursor:1}
        case $char in
            [[:space:]]) cursor=$((cursor + 1)); continue ;;
            ')')
                cursor=$((cursor + 1))
                (( ! redirection && ! printf_variable )) && [[ -z $command_prefix ]] &&
                    [[ $word_index -gt 0 || $consumed_redirect == 1 || $separator == ';' ]]
                return
                ;;
            '|'|'&'|';')
                (( (word_index > 0 || consumed_redirect) && ! redirection && ! printf_variable )) &&
                    [[ -z $command_prefix ]] || return 1
                case ${rhs:cursor:2} in
                    '||'|'&&') separator=${rhs:cursor:2}; cursor=$((cursor + 2)) ;;
                    *)
                        [[ $char != '&' ]] || return 1
                        separator=$char
                        cursor=$((cursor + 1))
                        ;;
                esac
                word_index=0
                consumed_redirect=0
                command=''
                echo_options=1
                printf_options=1
                printf_format=0
                component_options=1
                basename_suffix=0
                basename_multiple=0
                component_operands=0
                continue
                ;;
            '<'|'>')
                (( ! redirection )) || return 1
                case ${rhs:cursor:3} in
                    '<<<') redirection=2; cursor=$((cursor + 3)); continue ;;
                    *)
                        case ${rhs:cursor:2} in
                            '<<'|'>>'|'<>'|'>|') cursor=$((cursor + 2)) ;;
                            *) cursor=$((cursor + 1)) ;;
                        esac
                        ;;
                esac
                if [[ ${rhs:cursor} =~ $fd_target ]]; then
                    cursor=$((cursor + 1 + ${#BASH_REMATCH[1]}))
                    consumed_redirect=1
                else
                    redirection=1
                fi
                continue
                ;;
        esac

        if (( ! redirection )) && [[ ${rhs:cursor} =~ $redirect_fd ]]; then
            cursor=$((cursor + ${#BASH_REMATCH[1]}))
            continue
        fi
        literal=''
        segment=''
        has_expansion=0
        quote_mode=0
        if (( redirection == 2 )); then quote_mode=2; fi
        word_start=$cursor
        if [[ $command == basename || $command == dirname ]] && (( ! redirection )); then
            source=$rhs
            position=$cursor
            word_expansion=0
            read_shell_word '' 0 1
            (( position > cursor )) || return 1
            cursor=$position
            has_expansion=$word_expansion
            char=${rhs:cursor:1}
        fi
        while (( cursor < ${#rhs} )) && [[ ( $command != basename && $command != dirname ) || $redirection != 0 ]]; do
            char=${rhs:cursor:1}
            case $char in
                [[:space:]]|')'|'|'|'&'|';'|'<'|'>') break ;;
                '$')
                    if (( quote_mode )); then
                        quoted_segment_allowed "$segment" || return 1
                        segment=''
                    fi
                    consume_expansion || return 1
                    has_expansion=1
                    ;;
                "'")
                    cursor=$((cursor + 1))
                    while (( cursor < ${#rhs} )) && [[ ${rhs:cursor:1} != "'" ]]; do
                        literal+=${rhs:cursor:1}
                        segment+=${rhs:cursor:1}
                        cursor=$((cursor + 1))
                    done
                    [[ ${rhs:cursor:1} == "'" ]] || return 1
                    cursor=$((cursor + 1))
                    ;;
                '"') scan_double_quoted "$quote_mode" || return 1 ;;
                '\'|'`'|'(') return 1 ;;
                *)
                    literal+=$char
                    segment+=$char
                    cursor=$((cursor + 1))
                    ;;
            esac
        done

        if (( redirection )); then
            if (( redirection == 2 )); then
                (( has_expansion )) && quoted_segment_allowed "$segment" || return 1
            else
                (( has_expansion )) || [[ $literal == */* ]] || return 1
            fi
            redirection=0
            consumed_redirect=1
            continue
        fi
        if (( word_index == 0 )); then
            if (( ! prefix_operand )) && [[ $command_prefix == env &&
                    $literal =~ $assignment ]]; then
                [[ $literal == "${BASH_REMATCH[0]}" || ! ${literal%%=*} =~ $credential_keyword ]] || return 1
                prefix_options=0
                env_operands_started=1
                continue
            fi
            if (( prefix_operand )) || [[ -n $literal || $command_prefix == timeout ]]; then
                if consume_command_prefix "$literal" "$has_expansion"; then
                    [[ -z $prefix_split && $prefix_operand != 2 ]] || return 1
                    continue
                fi
            fi
            command=${literal##*/}
            if (( command_query )); then command=command; fi
            command_prefix=''
            prefix_options=0
            env_operands_started=0
            command_query=0
        elif [[ $command == echo ]]; then
            if (( echo_options && ! has_expansion )) && [[ $literal =~ ^-[neE]+$ ]]; then
                word_index=$((word_index + 1))
                continue
            fi
            [[ -z $literal || ( $has_expansion == 1 && $literal =~ $separators_only ) ]] || return 1
            echo_options=0
        elif [[ $command == printf ]]; then
            if (( printf_variable )); then
                (( ! has_expansion )) && [[ $literal =~ $identifier ]] || return 1
                printf_variable=0
            elif (( printf_options && ! has_expansion )) && [[ $literal == -v || $literal == -- ]]; then
                case $literal in
                    -v) printf_variable=1 ;;
                    --) printf_options=0 ;;
                esac
            elif (( ! printf_format )); then
                [[ -z $literal || $literal =~ $format_pattern ]] || return 1
                printf_format=1
                printf_options=0
            else
                [[ -z $literal || ( $has_expansion == 1 && $literal =~ $separators_only ) ]] || return 1
            fi
        elif [[ $command == basename || $command == dirname ]]; then
            if (( basename_suffix )); then
                basename_suffix=0
            elif (( component_options && ! has_expansion )) && [[ $literal == -* ]]; then
                if [[ $literal == -- ]]; then component_options=0; fi
                if [[ $command == basename ]]; then
                    case $literal in
                        -s|--suffix) basename_suffix=1; basename_multiple=1 ;;
                        -a|--multiple|-s?*|--suffix=*) basename_multiple=1 ;;
                    esac
                fi
            else
                component_options=0
                component_operands=$((component_operands + 1))
                if [[ $command != basename ]] || (( component_operands != 2 || basename_multiple )); then
                    if (( has_expansion )); then
                        validate_extraction_operand "${rhs:word_start:cursor-word_start}" || return 1
                    fi
                    stripped=${literal%"${literal##*[!/]}"}
                    if [[ ${nested_extraction:-} == unquoted ]]; then
                        # An unquoted nested result can split into multiple operands.
                        # Keep the conservative check for all of its components.
                        [[ $literal =~ $component_path ]] || return 1
                    elif [[ $command == basename ]]; then
                        [[ ${stripped##*/} =~ $separators_only ]] || return 1
                    elif [[ $stripped == */* ]]; then
                        if [[ ${nested_extraction:-} == quoted ]]; then
                            [[ ${stripped%/*} =~ $component_path ]] || return 1
                        else
                            [[ ${stripped%/*} =~ $separators_only ]] || return 1
                        fi
                    fi
                fi
            fi
        elif [[ -n $literal ]]; then
            if [[ $command == date && $literal == +* ]]; then
                [[ $literal =~ $date_format ]] || return 1
            elif [[ $command == cat && $literal =~ ^[A-Za-z0-9_-]+\.[A-Za-z0-9._-]+$ ]]; then
                : # A relative filename is a source of data, like a path containing a slash.
            elif [[ $command == git && $has_expansion == 0 &&
                    ( ( $word_index == 1 && $literal == rev-parse ) ||
                      ( $word_index == 2 && $previous_word == rev-parse && $literal == HEAD ) ) ]]; then
                : # The revision is resolved by Git.
            elif [[ $literal != -* && $literal != */* && ! $literal =~ ^[0-9]+$ && ! $literal =~ $escapes_only ]]; then
                # OpenSSL takes a subcommand before its options.
                [[ $word_index == 1 && $command == openssl && $literal == rand ]] || return 1
            fi
        fi
        previous_word=$literal
        word_index=$((word_index + 1))
    done
    return 1
}

expansion_only() {
    local boundary='^[[:space:];&|<>)]'
    while (( cursor < ${#rhs} )) && [[ ! ${rhs:cursor} =~ $boundary ]]; do
        case ${rhs:cursor:1} in
            '$') consume_expansion || return 1 ;;
            "'")
                [[ ${rhs:cursor:2} == "''" ]] || return 1
                cursor=$((cursor + 2))
                ;;
            '"') scan_expansion_quote || return 1 ;;
            *) return 1 ;;
        esac
    done
    (( cursor > 0 ))
}

credential_line_allowed() {
    local i
    for (( i=0; i<${#CREDENTIAL_LINE_ALLOWLIST[@]}; i+=2 )); do
        if [[ ${path#./} == "${CREDENTIAL_LINE_ALLOWLIST[i]}" &&
              $1 =~ ${CREDENTIAL_LINE_ALLOWLIST[i+1]} ]]; then
            return 0
        fi
    done
    return 1
}

# Track case patterns at each parenthesis depth inside a substitution.
# A pattern's optional opening parenthesis does not open a subshell.
track_substitution_case() {
    local depth=${#closers} state=${physical_cases[${#closers}]:-}
    local keyword='^([a-z]+|[!{])($|[[:space:];&|<>()])'
    local arm_end='^(;;&|;;|;&)'
    case ${state##*:} in
        subject)
            if (( word_start )) && [[ $char != [[:space:]] ]]; then
                physical_cases[depth]=${state%:*}:header
            fi
            return 1
            ;;
        header)
            if (( word_start )) && [[ ${physical:i} =~ $keyword && ${BASH_REMATCH[1]} == in ]]; then
                physical_cases[depth]=${state%:*}:pattern
            fi
            return 1
            ;;
        pattern)
            case $char in
                '(')
                    if (( i > 0 )) && [[ ${physical:i-1:1} == [@+*?!] ]]; then
                        pattern_groups[depth]=$(( ${pattern_groups[depth]:-0} + 1 ))
                    fi
                    return 0
                    ;;
                ')')
                    if (( ${pattern_groups[depth]:-0} )); then
                        pattern_groups[depth]=$((pattern_groups[depth] - 1))
                    else
                        physical_cases[depth]=${state%:*}:body
                        physical_commands[depth]=1
                    fi
                    return 0
                    ;;
            esac
            if (( word_start && ! ${pattern_groups[depth]:-0} )) &&
                    [[ ${physical:i} =~ $keyword && ${BASH_REMATCH[1]} == esac ]]; then
                physical_cases[depth]=${state%:*}
            fi
            return 1
            ;;
        body)
            if [[ ${physical:i} =~ $arm_end ]]; then
                physical_cases[depth]=${state%:*}:pattern
                i=$((i + ${#BASH_REMATCH[0]} - 1))
                return 0
            fi
            ;;
    esac
    case $char in
        [\;\&\|\(]) physical_commands[depth]=1 ;;
        [[:space:]]|'#') ;;
        *)
            if (( word_start && ${physical_commands[depth]:-1} )); then
                # State 2 keeps command position open for time's options.
                if (( ${physical_commands[depth]:-1} == 2 )) && [[ $char == '-' ]]; then
                    return 1
                fi
                if [[ ${physical:i} =~ $keyword ]]; then
                    case ${BASH_REMATCH[1]} in
                        case) physical_cases[depth]=${state}:subject ;;
                        'esac') physical_cases[depth]=${state%:*} ;;
                        time) physical_commands[depth]=2; return 1 ;;
                        if|while|until|then|do|else|elif|'!'|'{')
                            physical_commands[depth]=1
                            return 1
                            ;;
                    esac
                fi
                physical_commands[depth]=0
            fi
            ;;
    esac
    return 1
}

# Clip comments, queue heredocs, and track nesting across physical lines.
# Entering an expansion suspends the enclosing quote until its closer.
strip_credential_comment() {
    local physical=$1 i=0 char pair source=$1 position literal
    local strip_tabs delimiter_quoted delimiter_tail depth parameter_quoted=0
    local array_head='(^|[[:space:];&|()])[A-Za-z_][A-Za-z0-9_]*\+?=$'
    local conditional_prefix='(^|[;&|({])[[:blank:]]*((if|elif|while|until|then|else|do|!)[[:blank:]]+)*$'
    code=$physical
    continues=0
    while (( i < ${#physical} )); do
        char=${physical:i:1}
        pair=${physical:i:2}
        parameter_quoted=0
        if [[ $closers == *'}' ]]; then parameter_quoted=${quoted_parameters[${#closers}]:-0}; fi
        if [[ -z $quote && $closers == *')' ]] && track_substitution_case; then
            word_start=1
        elif [[ $char == '\' && $quote != "'" ]]; then
            if (( i + 1 == ${#physical} )); then
                continues=1
                return
            fi
            word_start=0
            i=$((i + 2))
            continue
        elif [[ $quote == '`' ]]; then
            # Read legacy substitution text before interpreting its contents.
            if [[ $char == '`' ]]; then
                backtick_ends[backtick_start]=$((${#logical} + i - ${#prefix}))
                quote=$backtick_quote
            fi
            word_start=0
        elif [[ $quote != "'" && $char == '`' ]]; then
            backtick_start=$((${#logical} + i - ${#prefix}))
            backtick_quote=$quote
            quote='`'
            word_start=0
        elif [[ ( $quote != "'" && ( $pair == '$(' || $pair == '${' ) ) ||
                ( -z $quote && $closers != *[A\}] && -z ${condition_levels[${#closers}]:-} &&
                  ( $pair == '<(' || $pair == '>(' ) ) ]]; then
            expansion_quotes+=("$quote")
            if [[ $pair == '${' && $quote == heredoc ]]; then
                heredoc_parameters+=("$(( ${#logical} + i - ${#prefix} ))")
            fi
            case ${physical:i:3} in
                '$((')
                    closers+='AA'
                    expansion_quotes+=('')
                    arithmetic_starts[$((${#closers} - 1))]=$((${#logical} + i - ${#prefix}))
                    i=$((i + 1))
                    ;;
                '$('*|'<('*|'>('*)
                    closers+=')'
                    substitution_starts[${#closers}]=$((${#logical} + i - ${#prefix}))
                    ;;
                *)
                    closers+='}'
                    if { [[ $quote == '"' ]] || (( parameter_quoted )); } &&
                            [[ ${physical:i+2} =~ $parameter_word_prefix ]]; then
                        quoted_parameters[${#closers}]=1
                    fi
                    ;;
            esac
            quote=''
            word_start=1
            i=$((i + 2))
            continue
        elif [[ -n $quote ]]; then
            if [[ $quote != heredoc && $char == "$quote" ]]; then quote=''; fi
            word_start=0
        elif [[ -n $closers && ( ( $closers != *A && $char == "${closers: -1}" ) ||
                ( $char == ')' && $closers == *A ) ) ]]; then
            depth=${#closers}
            if [[ -n ${substitution_starts[depth]:-} ]]; then
                substitution_ends[${substitution_starts[depth]}]=$((${#logical} + i - ${#prefix}))
            fi
            if [[ -n ${arithmetic_starts[depth]:-} ]]; then
                arithmetic_ends[${arithmetic_starts[depth]}]=$((${#logical} + i - ${#prefix}))
                unset 'arithmetic_starts[depth]'
            fi
            unset 'condition_levels[depth]' 'physical_cases[depth]' 'physical_commands[depth]' 'pattern_groups[depth]'
            unset 'substitution_starts[depth]' 'quoted_parameters[depth]'
            quote=${expansion_quotes[-1]}
            unset 'expansion_quotes[-1]'
            closers=${closers%?}
            word_start=0
        elif [[ $closers != *'}' && $pair == '((' ]]; then
            # A marks an arithmetic parenthesis, where << is a shift.
            closers+='AA'
            expansion_quotes+=('' '')
            word_start=1
            i=$((i + 2))
            continue
        else
            case $char in
                "'")
                    if (( ! parameter_quoted )); then quote=$char; fi
                    word_start=0
                    ;;
                '"'|'`') quote=$char; word_start=0 ;;
                '(')
                    if [[ $closers == *')' || $closers == *A ]]; then
                        closers+=${closers: -1}
                        expansion_quotes+=('')
                    elif [[ -z $closers && ${physical:0:i} =~ $array_head ]]; then
                        closers+=')'
                        expansion_quotes+=('')
                    fi
                    word_start=1
                    ;;
                '['|']')
                    if (( word_start )) && [[ -z ${physical:i+2:1} || ${physical:i+2:1} == [[:space:]\;\&\|\)] ]]; then
                        case $pair in
                            '[[')
                                if [[ ${physical:0:i} =~ $conditional_prefix ]]; then
                                    condition_levels[${#closers}]=1
                                fi
                                ;;
                            ']]') unset 'condition_levels[${#closers}]' ;;
                        esac
                    fi
                    word_start=0
                    ;;
                '<')
                    if [[ ${physical:i:3} == '<<<' ]]; then
                        i=$((i + 3))
                        word_start=1
                        continue
                    elif [[ $pair == '<<' && $closers != *[A\}] &&
                            -z ${condition_levels[${#closers}]:-} ]]; then
                        position=$i
                        if read_credential_heredoc; then
                            if [[ $delimiter_tail == continuation ]]; then
                                pending_heredoc=${physical:i:position-i-1}
                                continues=1
                                return
                            elif [[ $delimiter_tail == quote ]]; then
                                pending_heredoc=${physical:i:position-i}$'\n'
                                return
                            fi
                            register_credential_heredoc "$logical_start:$(( ${#logical} + i - ${#prefix} ))"
                            heredoc_delimiters+=("$literal")
                            heredoc_strip_tabs+=("$strip_tabs")
                            heredoc_quoted+=("$delimiter_quoted")
                            i=$position
                            word_start=0
                            continue
                        fi
                    fi
                    word_start=1
                    ;;
                '#')
                    if (( word_start )) && [[ $closers != *'}' ]]; then
                        code=${physical:0:i}
                        break
                    fi
                    word_start=0
                    ;;
                [[:space:]\;\&\|\)\>]) word_start=1 ;;
                *) word_start=0 ;;
            esac
        fi
        i=$((i + 1))
    done
    word_start=1
    if [[ -z $quote && $closers == *')' ]]; then
        physical_commands[${#closers}]=1
    fi
}

credential_source_line() {
    local start=$1 offset_index
    credential_line=$logical_start
    for offset_index in "${!continued_offsets[@]}"; do
        (( continued_offsets[offset_index] <= start )) || break
        credential_line=${continued_lines[offset_index]}
    done
}

report_credential_assignment() {
    local credential_line owner=${function_depth:-0} scope
    (( ! ${suppress_command_writes:-0} && ! ${arithmetic_expansion_failed:-0} )) || return 0
    (( ! discovering_credential_inputs && ! suppress_credential_reports && ! ${#aborted_credential_shells[@]} )) || return 0
    if (( dead_branches )); then
        for scope in "${isolated_scopes[@]}"; do
            if [[ ${loop_body_sites[$scope]+present} || ${empty_loop_bodies[$scope]+present} ]]; then return 0; fi
        done
    fi
    if (( ! owner )); then owner=${credential_report_owner:-0}; fi
    credential_source_line "$1"
    if (( owner && owner != diagnostic_function )); then
        if (( ${state_dependent_credential:-0} )) ||
                [[ ${returned_functions[$owner]+present} || ${function_may_abort[$owner]+present} ]]; then
            definition_lines[$owner:$credential_line]=1
        elif (( ! diagnostic_function )); then
            report_credential_line "$credential_line"
        fi
    elif (( diagnostic_function )); then
        if (( ! dead_branches )); then report_credential_line "$credential_line"; fi
    else
        report_credential_line "$credential_line"
    fi
}

report_credential_line() {
    local credential_line=$1
    (( ! ${computing_heredoc_input:-0} )) || return 0
    if (( diagnostic_function )); then
        printf 'line %s\n' "$credential_line"
        return 0
    fi
    if [[ -z ${reported_lines[credential_line]:-} ]]; then
        report "$credential_line" credential-literal
        reported_lines[credential_line]=1
    fi
}

read_credential_heredoc() {
    position=$((position + 2))
    strip_tabs=0
    if [[ ${source:position:1} == '-' ]]; then
        strip_tabs=1
        position=$((position + 1))
    fi
    while [[ ${source:position:1} == [[:blank:]] ]]; do
        position=$((position + 1))
    done
    literal=''
    delimiter_quoted=0
    delimiter_tail=''
    [[ ${source:position:1} != '#' ]] || return 1
    local start=$position
    # Delimiter words undergo quote removal without parameter expansion.
    read_shell_word '' 0 0 1
    (( position > start ))
}

# Bodies are registered separately from their descriptor bindings. All queued
# bodies occupy source lines, even when a later redirection replaces stdin.
register_credential_heredoc() {
    local key=$1 cursor body_line text='' delimiter=$literal
    if (( ! ${#heredoc_delimiters[@]} )); then
        heredoc_body_cursor=$((line - source_first_line + 1))
    fi
    for (( cursor=heredoc_body_cursor; cursor<${#source_physical_lines[@]}; cursor++ )); do
        body_line=${source_physical_lines[cursor]}
        if (( strip_tabs )); then
            while [[ $body_line == $'\t'* ]]; do body_line=${body_line#$'\t'}; done
        fi
        if [[ $body_line == "$delimiter" ]]; then
            registered_heredocs[$key]=$text
            registered_heredoc_quotes[$key]=$delimiter_quoted
            heredoc_body_cursor=$((cursor + 1))
            return
        fi
        text+=$body_line$'\n'
    done
}

# Heredoc quotes are literal, while $, backticks and selected backslashes
# retain double-quote expansion semantics. Unknown lines have their own marker
# so consuming one does not discard subsequent known lines in the stream.
expand_credential_heredoc() {
    local text=$1 body_line encoded char next index literal word_expansion expansion_end
    local computing_heredoc_input=1
    local joins_next='(^|[^\\])(\\\\)*\\$'
    local -a substitution_starts=() arithmetic_starts=() expansion_quotes=()
    local -a physical_cases=() physical_commands=() pattern_groups=() heredoc_parameters=()
    local backtick_start backtick_quote
    local source position scan_word_substitutions=1 capture_heredoc_output=1
    local logical prefix='' code quote='' closers='' word_start=1 continues=0
    local -a substitution_ends=() arithmetic_ends=() backtick_ends=()
    local -a scanned_substitutions=() scanned_backticks=() quoted_parameters=() condition_levels=()
    local -a heredoc_delimiters=() heredoc_strip_tabs=() heredoc_quoted=()
    local -a env_expansions=() env_expansion_quotes=()
    local -A substitution_values=() substitution_statuses=()
    local assignment_substitution_status=0
    expanded_heredoc=''
    while [[ -n $text ]]; do
        body_line=${text%%$'\n'*}
        text=${text#*$'\n'}
        # Locate expansions before quoting the literal heredoc text. Their
        # interior quotes belong to shell words, not to the heredoc itself.
        # Join escaped newlines before finding spans, and retain complete
        # multiline substitutions as a single expansion unit.
        while :; do
            while [[ -n $text && $body_line =~ $joins_next ]]; do
                body_line=${body_line%\\}
                body_line+=${text%%$'\n'*}; text=${text#*$'\n'}
            done
            logical='' quote=heredoc closers='' word_start=1
            substitution_starts=() arithmetic_starts=() expansion_quotes=()
            substitution_ends=() arithmetic_ends=() backtick_ends=()
            quoted_parameters=() condition_levels=()
            physical_cases=() physical_commands=() pattern_groups=()
            strip_credential_comment "$body_line"
            if [[ -n $text && ( -n $closers || $quote == '`' ) ]]; then
                body_line+=$'\n'${text%%$'\n'*}; text=${text#*$'\n'}
            else
                break
            fi
        done
        quote='' closers=''
        encoded='"'
        for (( index=0; index<${#body_line}; index++ )); do
            char=${body_line:index:1} next=${body_line:index+1:1}
            expansion_end=${substitution_ends[index]:-${arithmetic_ends[index]:-${backtick_ends[index]:-}}}
            if [[ -n $expansion_end ]]; then
                encoded+=${body_line:index:expansion_end-index+1}
                index=$expansion_end
            elif [[ $char == '"' ]]; then encoded+='\"'
            elif [[ $char == '\' && $next == @('$'|'`'|'\') ]]; then
                encoded+=$char$next; index=$((index + 1))
            elif [[ $char == '\' ]]; then encoded+='\\'
            else encoded+=$char
            fi
        done
        source=$encoded'"' position=0 literal='' word_expansion=0
        logical=''
        substitution_ends=() arithmetic_ends=() backtick_ends=()
        scanned_substitutions=() scanned_backticks=()
        strip_credential_comment "$source"
        logical=$source
        read_shell_word '' 0 2 0 1
        if (( ! word_expansion )) || resolve_env_word 1 1 1; then
            expanded_heredoc+=$literal$'\n'
        else
            expanded_heredoc+=$'\034unknown-input\034\n'
        fi
    done
}

# Consume one redirection, including its optional FD and target word.
skip_credential_redirection() {
    local redirect_start=$position process_input=unknown process_sub_input=0 heredoc_key
    local execute_redirection=${1:-0} suppressed_redirection=0
    local skip_expansion_effects=${skip_expansion_effects:-0}
    if (( execute_redirection && ${command_redirection_failed:-0} )); then
        execute_redirection=0 suppressed_redirection=1 skip_expansion_effects=1
    fi
    local operator="^(${fd_prefix}?"'(<<<|<<|<>|<&|>>|>\||>&|<|>)|&>>?)'
    local literal='' word_expansion=0 strip_tabs delimiter_quoted delimiter_tail redirect fd matched
    [[ ${source:position} =~ $operator ]] || return 1
    matched=${BASH_REMATCH[0]}
    fd=${BASH_REMATCH[2]}
    redirect=${BASH_REMATCH[3]}
    if [[ $matched == '&>'* ]]; then redirect=${matched#&}; fi
    heredoc_key=${redirection_logical_start:-$logical_start}:$((redirect_start + ${#fd} + ${redirection_offset:-0}))
    if (( execute_redirection )) && [[ $redirect != @(<<|<<<) ]]; then
        command_redirection_unknown=1
    fi
    if [[ $redirect == '<<' ]]; then
        position=$((position + ${#BASH_REMATCH[2]}))
        read_credential_heredoc || :
    else
        position=$((position + ${#BASH_REMATCH[0]}))
        while [[ ${source:position:1} == [[:blank:]] ]]; do
            position=$((position + 1))
        done
        case ${source:position:2} in
            '<('|'>(')
                word_expansion=1
                if [[ -n ${substitution_ends[position]:-} ]]; then
                    if (( execute_redirection )) && [[ ${source:position:2} == '<(' ]]; then
                        process_sub_input=1
                        local substitution_value=unknown substitution_status=2
                        scan_credential_substitution "$position" stream
                        if [[ $substitution_value == scalar:* ]]; then
                            process_input=bytes:${substitution_value#scalar:}
                        fi
                        scanned_substitutions[position]=1
                    fi
                    position=$((substitution_ends[position] + 1))
                else
                    position=$((position + 2))
                    read_shell_word ')'
                fi
                ;;
            *) read_shell_word '' 0 2 0 "${1:-0}" ;;
        esac
    fi
    if (( suppressed_redirection )); then
        local expansion_start=$redirect_start
        skip_credential_substitution_effects "$position"
    fi
    if (( execute_redirection )); then
        local target_fd=$fd binding=unknown input_source
        if [[ -z $target_fd ]]; then
            case $redirect in '>'*) target_fd=1 ;; *) target_fd=0 ;; esac
        fi
        if [[ $target_fd =~ ^[0-9]+$ ]]; then
            if (( word_expansion && ! process_sub_input )) && [[ $redirect != '<<' ]] && resolve_env_word 1 1; then word_expansion=0; fi
            if (( ! word_expansion )); then
                case $redirect in
                    '<<<') binding=literal:$literal ;;
                    '<'|'<>') binding=path:$literal ;;
                    '>'|'>>'|'>|')
                        local output_path
                        if output_path=$(credential_file_path "$literal"); then binding=output:$output_path; fi
                        ;;
                    '<<')
                        if [[ ${registered_heredocs[$heredoc_key]+present} ]]; then
                            local expanded_heredoc=${registered_heredocs[$heredoc_key]}
                            if (( ! ${registered_heredoc_quotes[$heredoc_key]} )); then
                                expand_credential_heredoc "$expanded_heredoc"
                            fi
                            binding=bytes:$expanded_heredoc
                        fi
                        ;;
                    '<&'|'>&')
                        if [[ $literal =~ ^[0-9]+$ ]]; then
                            credential_descriptor_source "$literal"
                            binding=$input_source
                            if [[ $binding == @(closed|unbound) ]] && (( ! command_redirection_failed )); then
                                command_redirection_failed=1
                                suppress_command_writes=1
                            fi
                        elif [[ $literal == - ]]; then
                            binding=closed
                        fi
                        ;;
                esac
            elif [[ $redirect == '<' ]]; then binding=$process_input
            fi
            if (( ! word_expansion )) && [[ $redirect == @('<'|'>'|'>>'|'>|'|'<>') ]] &&
                    credential_file_redirection_fails "$literal" "$redirect"; then
                command_redirection_failed=1
                suppress_command_writes=1
            fi
            # A failed open/duplication does not install a descriptor or
            # perform any later redirection. Earlier opens are journaled below.
            (( ! command_redirection_failed )) || return 0
            command_fds[$target_fd]=$binding
            if [[ $target_fd == 0 ]]; then command_input=$binding; fi
        fi
    fi
    if (( execute_redirection && ! word_expansion )) && [[ -n $literal ]]; then
        # Opening a file is separate from running the command. In particular,
        # preflight may suppress builtin writes, but not successful opens.
        local suppress_command_writes=0
        case $redirect in
            '>'|'>|') apply_credential_output "$literal" literal: ;;
            '>>'|'<>')
                local file contents=''
                file=$(credential_file_path "$literal") || file=''
                if [[ -n $file ]]; then
                    credential_file_content "$file"
                    if [[ -z $contents && ! -e $file && ! -L $file &&
                          $file == "$scan_root/"* && -d ${file%/*} ]]; then contents=literal:; fi
                    apply_credential_output "$literal" "$contents"
                fi
                ;;
        esac
        case $redirect in
            '>'|'>|')
                if [[ $fd == @(''|1) ]]; then
                    command_outputs[$literal]=truncate
                elif [[ ! ${command_outputs[$literal]+present} ]]; then
                    command_outputs[$literal]=truncate-other
                fi
                ;;
            '>>')
                if [[ $fd == @(''|1) ]]; then
                    case ${command_outputs[$literal]:-} in
                        truncate|truncate-other) command_outputs[$literal]=truncate ;;
                        *) command_outputs[$literal]=append ;;
                    esac
                elif [[ ! ${command_outputs[$literal]+present} ]]; then
                    command_outputs[$literal]=write
                fi
                ;;
            '<>') command_outputs[$literal]=write ;;
        esac
    fi
    return 0
}

# Prove absence only within the scanned tree and modeled cwd. Files written
# earlier and directories possibly created by mkdir prevent an absence proof.
credential_file_redirection_fails() {
    local file parent created id output opened
    # Unresolved pathname/tilde/brace expansion cannot prove absence.
    [[ $1 != '~'* && $1 != *[\*\?\[\]\{\}]* ]] || return 1
    file=$(credential_file_path "$1") || return 1
    if [[ $2 == '>' ]] && (( ${command_noclobber:-$noclobber} )); then
        id=${credential_file_ids[$file]:-}
        # Journaled writes establish existence even outside the scan root;
        # host filesystem evidence is restricted to the scanned tree.
        if [[ ( -n $id && ${credential_file_contents[$id]+present} ) ||
              ( $file == "$scan_root/"* && -f $file ) ]]; then
            return 0
        fi
    fi
    [[ $file == "$scan_root/"* ]] || return 1
    parent=${file%/*}
    if [[ -d $parent ]]; then
        [[ $2 == '<' && ! -e $file && ! -L $file ]] || return 1
        # Earlier redirects in this same command have already opened their
        # targets, even though the command's output is journaled at completion.
        for output in "${!command_outputs[@]}"; do
            opened=$(credential_file_path "$output") || continue
            [[ $opened != "$file" ]] || return 1
        done
        id=${credential_file_ids[$file]:-}
        [[ -z $id || ! ${credential_file_contents[$id]+present} ]] || return 1
    else
        [[ ! -e $parent && ! -L $parent ]] || return 1
    fi
    while IFS= read -r created; do
        [[ -n $created ]] || continue
        if [[ $created == '*' || $parent == "$created" ||
              $parent == "$created/"* || $created == "$parent/"* ]]; then return 1; fi
    done <<< "$credential_created_directories"
    return 0
}

# Evaluate trailing redirects at entry/call time, before enclosed effects.
# Discovery stores syntax, not a failure inferred from the body's exit state.
inspect_credential_redirections() {
    local descriptor redirection_origin=${2:-$logical_start:0}
    local redirection_logical_start=${redirection_origin%:*} redirection_offset=${redirection_origin#*:}
    local -A inherited_fds=()
    for descriptor in "${!command_fds[@]}"; do inherited_fds[$descriptor]=${command_fds[$descriptor]}; done
    local source=$1 position=0 command_redirection_failed=0 command_input=''
    local command_redirection_unknown=0 suppress_command_writes=0
    local skip_expansion_effects=1 scan_word_substitutions=0
    local -a env_expansions=() env_expansion_quotes=() substitution_ends=()
    local -a scanned_substitutions=() scanned_backticks=()
    local logical=$source opening ending
    for opening in ${source_substitution_starts[$redirection_logical_start]:-}; do
        ending=${source_substitutions[$redirection_logical_start:$opening]}
        if (( opening >= redirection_offset && ending < redirection_offset + ${#source} )); then
            substitution_ends[opening-redirection_offset]=$((ending - redirection_offset))
        fi
    done
    local -A command_fds=() command_outputs=()
    for descriptor in "${!inherited_fds[@]}"; do command_fds[$descriptor]=${inherited_fds[$descriptor]}; done
    collect_credential_compound_redirections 1
    redirection_failed=$command_redirection_failed
    for descriptor in "${!command_fds[@]}"; do redirected_fds[$descriptor]=${command_fds[$descriptor]}; done
}

# Declarations and a few builtins apply facts while parsing their operands.
# Check their remaining redirects first so a later bad duplication prevents
# those effects too. The subshell discards discovery facts, stream cursors and
# diagnostics; this evaluates only the checker model, never shell source.
credential_redirections_fail() (
    local redirection_preflight=1 command_redirection_failed=0
    local position=$position start literal='' word_expansion=0
    local scan_word_substitutions=0 skip_expansion_effects=1
    while (( position < ${#source} )); do
        case ${source:position:1} in
            [[:blank:]]) position=$((position + 1)); continue ;;
            $'\n'|';'|'&'|'|'|'('|')'|'{'|'}') return 1 ;;
        esac
        if skip_credential_redirection 1; then
            if (( command_redirection_failed )); then return 0; fi
        else
            start=$position
            read_shell_word '' 0 0 0 0
            (( position > start )) || return 1
        fi
    done
    return 1
)

# A duplicated descriptor shares the same open stream cursor. Command-local
# redirections take precedence without advancing an enclosing stream.
credential_descriptor_source() {
    local descriptor=$1 scope scope_id
    input_source=unknown
    if [[ ${command_fds[$descriptor]+present} ]]; then
        input_source=${command_fds[$descriptor]}
        bind_credential_input
        open_credential_stream
        command_fds[$descriptor]=$input_source
        return
    fi
    if [[ $descriptor == 0 && -n $command_input ]]; then input_source=$command_input; return; fi
    if [[ $descriptor == 0 ]] && (( pipeline_next )); then
        input_source=${pipeline_input:-unknown}
        return
    fi
    for (( scope=${#isolated_scopes[@]}-1; scope>=0; scope-- )); do
        scope_id=${isolated_scopes[scope]}
        if [[ ${scope_descriptors[$scope_id:$descriptor]+present} ]]; then
            input_source=${scope_descriptors[$scope_id:$descriptor]}
            return
        fi
        if [[ $descriptor == 0 && -n ${scope_inputs[$scope_id]:-} ]]; then
            input_source=${scope_inputs[$scope_id]}
            if (( ${2:-0} )); then input_source=${scope_replay_inputs[$scope_id]:-$input_source}; fi
            return
        fi
    done
    input_source=${persistent_descriptors[$descriptor]:-unknown}
    if [[ $descriptor =~ ^[0-9]+$ && $descriptor != @(0|1|2) &&
          ! ${persistent_descriptors[$descriptor]+present} ]]; then input_source=unbound; fi
}

credential_input_source() {
    credential_descriptor_source "${read_descriptor:-0}" "${1:-0}"
}

credential_file_path() {
    if [[ $1 == /* ]]; then
        realpath -m -- "$1" 2>/dev/null
    elif [[ -n $credential_directory ]]; then
        realpath -m -- "$credential_directory/$1" 2>/dev/null
    else
        return 1
    fi
}

apply_credential_output() {
    local file key contents=$2 scope=${isolated_scopes[-1]}
    (( ! dead_branches )) || return 0
    if (( ! replaying_function )) && records_credential_effects "$scope"; then
        record_credential_function_effect "$scope" output "$1" "$2" 0
    fi
    file=$(credential_file_path "$1") || return 0
    if [[ ! ${credential_file_ids[$file]+present} ]]; then
        credential_file_ids[$file]=${#credential_file_ids[@]}
    fi
    key=file:${credential_file_ids[$file]}
    if [[ $contents == append:literal:* ]]; then
        contents=''
        credential_file_content "$file"
        if [[ $contents == literal:* ]]; then contents+=${2#append:literal:}; fi
    fi
    local replaying_function=1
    change_credential_state "$key" "${contents:-unknown}"
}

# A trailing arithmetic operator means the RHS is an expression.
arithmetic_number='(0[xX][[:xdigit:]]+|[0-9]+#[[:alnum:]@_]+|[0-9]+)'
arithmetic_number_default='\$\{([A-Za-z_][A-Za-z0-9_]*|[0-9]+|[?#@*!$-]):?[-=+]'
arithmetic_assignment_prefix='[[:space:]]*([+*/%&|^-]|<<|>>)?=[[:space:]]*[+-]?'

arithmetic_assignment_has_literal() {
    local expression=$1 boundary=$2 depth=0 i char
    local comparison='^(==|!=|<=|>=)'
    if [[ $expression == '['* ]]; then
        for (( i=0; i<${#expression}; i++ )); do
            char=${expression:i:1}
            case $char in
                '[') depth=$((depth + 1)) ;;
                ']')
                    depth=$((depth - 1))
                    if (( ! depth )); then break; fi
                    ;;
                '<'|'>')
                    case ${expression:i:3} in '<<='|'>>=') return 1 ;; esac
                    ;&
                '='|'!')
                    if [[ ${expression:i} =~ $comparison ]]; then
                        i=$((i + 1))
                    elif [[ $char == '=' ]]; then
                        return 1
                    fi
                    ;;
            esac
        done
        (( ! depth )) || return 1
        expression=${expression:i+1}
    fi
    if [[ $expression =~ ^$arithmetic_assignment_prefix$arithmetic_number[[:space:]]*$boundary ]]; then
        return 0
    fi
    [[ $expression =~ ^$arithmetic_assignment_prefix$arithmetic_number_default ]] || return 1
    local source=${expression:${#BASH_REMATCH[0]}} position=0 literal='' word_expansion=0
    local delimiter_quoted=0 delimiter_tail=''
    read_shell_word '}' 0 0 1
    [[ ${source:position-1:1} == '}' && -z $delimiter_tail &&
        $literal =~ ^[+-]?$arithmetic_number$ &&
        ${source:position} =~ ^[[:space:]]*$boundary ]]
}

scan_credential_arithmetic() {
    local position=$1 end=$2 scan_parameters=${3:-0} char name start nested_end brackets=0
    local literal='' word_expansion=0
    local ident='^[A-Za-z_][A-Za-z0-9_]*'
    local token='^[A-Za-z0-9_#@]+'
    while (( position < end )); do
        # Nested command bodies are scanned independently. Their text is
        # data from the perspective of the enclosing arithmetic expression.
        nested_end=${substitution_ends[position]:-${backtick_ends[position]:-${arithmetic_ends[position]:-}}}
        if [[ -n $nested_end ]]; then
            if (( scan_parameters )); then
                if [[ -n ${substitution_ends[position]:-} ]]; then
                    scan_credential_word_substitution "$position" command
                elif [[ -n ${backtick_ends[position]:-} ]]; then
                    scan_credential_word_substitution "$position" backtick
                fi
            fi
            position=$((nested_end + 1))
            continue
        fi
        char=${source:position:1}
        case $char in
            '$')
                if [[ ${source:position+1:1} == '{' ]]; then
                    start=$position
                    position=$((position + 2))
                    if (( ! scan_parameters )) || ! scan_credential_parameter "$start" 0; then
                        read_shell_word '}' 0 0 0 1
                    fi
                    continue
                fi
                ;;
            '[') brackets=$((brackets + 1)) ;;
            ']') brackets=$((brackets - 1)) ;;
            [A-Za-z_])
                if (( ! brackets )) && [[ ${source:position:end-position} =~ $ident ]]; then
                    start=$position
                    name=${BASH_REMATCH[0]}
                    position=$((position + ${#name}))
                    if [[ $name =~ $credential_keyword && ${name,,} != *_count ]] &&
                            arithmetic_assignment_has_literal "${source:position:end-position}" '([),:;]|$)'; then
                        report_credential_assignment "$start"
                    fi
                    continue
                fi
                ;;
            [0-9])
                if [[ ${source:position:end-position} =~ $token ]]; then
                    position=$((position + ${#BASH_REMATCH[0]}))
                    continue
                fi
                ;;
        esac
        position=$((position + 1))
    done
}

# GNU env's split-string syntax is an argv format, so shell operators in
# it are ordinary characters. Prepend the result to the unconsumed words.
env_expansion_marker=$'\001'
env_expansion_ref="^${env_expansion_marker}([0-9]+)${env_expansion_marker}"

# Preserve regex text and its quoting separately. Bash's ERE bracket rules
# cannot be reproduced by backslash-escaping each quoted character in isolation.
record_credential_regex_part() {
    (( ${capture_pattern:-0} == 3 )) || return 0
    regex_parts+=("$1")
    regex_quotes+=("${2:-${quote:+1}}")
}

record_env_expansion() {
    if (( ${capture_loop_expansions:-0} )) && [[ ${quote:-} != '"' ]]; then
        loop_unquoted_expansions+=("${#env_expansions[@]}")
    fi
    literal+="$env_expansion_marker${#env_expansions[@]}$env_expansion_marker"
    if (( ${capture_pattern:-0} )); then
        case_pattern+="$env_expansion_marker${#env_expansions[@]}$env_expansion_marker"
    fi
    record_credential_regex_part "$env_expansion_marker${#env_expansions[@]}$env_expansion_marker"
    env_expansion_quotes[${#env_expansions[@]}]=${quote:-}
    env_expansions+=("${source:$1:position-$1}")
}

# Recover shell expansion spelling from marked words. Keep argv text quoted
# for shell value analysis and unquoted when feeding the arithmetic parser.
restore_marked_value() {
    local input=$1 quote_literals=${2:-1} chunk quoted index expansion
    local simple_variable='^\$([A-Za-z_][A-Za-z0-9_]*|[0-9?#@*!$-])$'
    rhs=''
    while [[ -n $input ]]; do
        if [[ $input =~ $env_expansion_ref ]]; then
            index=${BASH_REMATCH[1]}
            input=${input:${#BASH_REMATCH[0]}}
            expansion=${env_expansions[index]}
            # Preserve the variable boundary when argv quote removal joins
            # the expansion to literal text.
            if [[ $expansion =~ $simple_variable ]]; then
                expansion='${'"${BASH_REMATCH[1]}"'}'
            fi
            rhs+=$expansion
        else
            chunk=${input%%"$env_expansion_marker"*}
            if [[ -z $chunk ]]; then chunk=${input:0:1}; fi
            if (( quote_literals )); then
                printf -v quoted '%q' "$chunk"
                rhs+=$quoted
            else
                rhs+=$chunk
            fi
            input=${input:${#chunk}}
        fi
    done
}

# Lookahead may precede the normal substitution scan. Evaluate only the
# checker model in a subshell: diagnostics, writes and stream cursors stay
# isolated, and the normal source pass still scans the substitution's effects.
credential_substitution_fact() (
    local reference=$1 opening substitution_value=unknown
    local skip_expansion_effects=0 scan_word_substitutions=1
    if [[ $reference == '$('* && $reference != '$(('* ]]; then
        for opening in "${!substitution_ends[@]}"; do
            if [[ ${logical:opening:substitution_ends[opening]+1-opening} == "$reference" ]]; then
                scan_credential_substitution "$opening" >/dev/null
                [[ $substitution_value == scalar:* ]] || return 1
                printf '%s' "$substitution_value"
                return
            fi
        done
    elif [[ $reference == '`'* ]]; then
        for opening in "${!backtick_ends[@]}"; do
            if [[ ${logical:opening:backtick_ends[opening]+1-opening} == "$reference" ]]; then
                scan_credential_backtick "$opening" "${backtick_ends[opening]}" >/dev/null
                [[ $substitution_value == scalar:* ]] || return 1
                printf '%s' "$substitution_value"
                return
            fi
        done
    fi
    return 1
)

# Resolve only variable references and literal text. Keep unresolved words
# transparent so later argv words can still establish the command position.
resolve_env_word() {
    local input=$literal resolved='' reference reference_quoted fact chunk name
    local pattern_list_subject=${2:-0}
    local variable='^[A-Za-z_][A-Za-z0-9_]*$'
    local removal='^\$\{([!#][A-Za-z_0-9]|([A-Za-z_][A-Za-z0-9_]*|[0-9]+|[#*@])(\[[^]]+\])?[@:#%^,?=+-])'
    local at_operator='^\$\{@:?[=+?-]'
    local list_replacement='^\$\{([@*]|[A-Za-z_][A-Za-z0-9_]*(\[[^]]+\])?)/'
    local array_reference='^\$\{[A-Za-z_][A-Za-z0-9_]*\[[@*]\]\}$'
    local element_reference='^([A-Za-z_][A-Za-z0-9_]*)(\[[^]]+\])$'
    while [[ -n $input ]]; do
        if [[ $input =~ $env_expansion_ref ]]; then
            reference=${env_expansions[BASH_REMATCH[1]]}
            reference_quoted=${env_expansion_quotes[BASH_REMATCH[1]]:-}
            input=${input:${#BASH_REMATCH[0]}}
            if [[ $reference == @('$('*|'`'*) && $reference != '$(('* ]]; then
                fact=${substitution_values[$reference]:-unknown}
                if (( ${skip_expansion_effects:-0} )) || [[ $fact != scalar:* ]]; then
                    fact=$(credential_substitution_fact "$reference") || return 1
                fi
                resolved+=${fact#scalar:}
                continue
            fi
            if (( ${3:-0} )) && [[ $reference == '$(('*'))' ]]; then
                chunk=$(credential_arithmetic_value "${reference:3:${#reference}-5}") || return 1
                resolved+=$chunk
                continue
            fi
            if (( pattern_list_subject )) && [[ $reference =~ $array_reference ]]; then
                local parameter_value=unknown array_name=${reference:2:${#reference}-3}
                resolve_credential_pattern_list "${array_name%\[*}" '#' '' '' "[${array_name#*\[}"
                [[ $parameter_value == scalar:* ]] || return 1
                resolved+=${parameter_value#scalar:}
                continue
            fi
            if [[ $reference =~ $array_reference && -z $input && -z $resolved ]]; then
                if (( ${1:-0} )); then return 1; fi
                # Known arrays are spliced before env parses the argv stream.
                # Preserve the conservative treatment of unknown argv.
                literal=$reference
                return 0
            fi
            if [[ $reference =~ $removal || $reference =~ $list_replacement ||
                    ( ${1:-0} == 1 && $reference =~ $at_operator ) ]]; then
                local parameter_value=unknown
                resolve_credential_parameter_value "$reference" "$reference_quoted" || return 1
                resolved+=${parameter_value#scalar:}
                continue
            fi
            reference=${reference#\$}
            reference=${reference#\{}
            reference=${reference%\}}
            if [[ $reference =~ $element_reference ]]; then
                local current
                credential_parameter_fact "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
                fact=$current
            elif [[ $reference =~ $variable ]]; then
                local current
                credential_parameter_fact "$reference" ''
                fact=$current
            elif [[ $reference =~ ^([0-9]+|#)$ ||
                    ( ${1:-0} == 1 && $reference == [*@] ) ]]; then
                local current
                credential_special_parameter_fact "$reference"
                fact=$current
            else
                return 1
            fi
            case $fact in
                unset) ;;
                partial-array:*)
                    local current
                    credential_parameter_fact "$reference" ''
                    case $current in
                        unset) ;;
                        scalar:*) resolved+=${current#scalar:} ;;
                        *) return 1 ;;
                    esac
                    ;;
                scalar:*) resolved+=${fact#*:} ;;
                array:*)
                    name=${fact#array:}
                    if [[ ${name%:*} != "${name#*:}" && ! ${sparse_array_bounds[$name]+present} ]]; then
                        resolved+=${known_array_words[${name%:*}]}
                    fi
                    ;;
                *) return 1 ;;
            esac
        else
            chunk=${input%%"$env_expansion_marker"*}
            [[ -n $chunk ]] || return 1
            resolved+=$chunk
            input=${input:${#chunk}}
        fi
    done
    [[ -n $resolved || ${1:-0} == 1 ]] || return 1
    literal=$resolved
}

# Literal pattern text already carries quote escapes from read_shell_word.
# Resolve each expansion separately so quoted output loses pattern meaning.
# Modes: 1 glob, 2 replacement (& and backslash), 3 extended regex.
resolve_credential_glob_pattern() {
    local input=$case_pattern resolved='' literal index marker quoted character char
    local pattern_mode=${1:-1}
    while [[ -n $input ]]; do
        if [[ $input =~ $env_expansion_ref ]]; then
            marker=${BASH_REMATCH[0]} index=${BASH_REMATCH[1]}
            input=${input:${#marker}}
            literal=$marker
            resolve_env_word 1 1 || return 1
            if [[ ${env_expansion_quotes[index]:-} == '"' ]]; then
                quoted=''
                for (( character=0; character<${#literal}; character++ )); do
                    char=${literal:character:1}
                    if (( pattern_mode == 1 )) ||
                            { (( pattern_mode == 2 )) && [[ $char == [\\\&] ]]; } ||
                            { (( pattern_mode == 3 )) && [[ $char == [\\.\^\$\*+?\(\)\[\]\{\}\|] ]]; }; then
                        quoted+='\'
                    fi
                    quoted+=$char
                done
                resolved+=$quoted
            else
                resolved+=$literal
            fi
        else
            literal=${input%%"$env_expansion_marker"*}
            [[ -n $literal ]] || return 1
            resolved+=$literal
            input=${input:${#literal}}
        fi
    done
    case_pattern=$resolved
}

# Pass only literal data to Bash's field splitter. NUL records preserve empty
# fields and embedded newlines, and disabling globbing keeps results static.
split_credential_fields() (
    local IFS=$1 expansion=$2
    local -a fields
    shift 2
    set -f
    case $expansion in
        '@') fields=( $@ ) ;;
        '*') fields=( $* ) ;;
        scalar) fields=( $1 ) ;;
    esac
    if (( ${#fields[@]} )); then printf '%s\0' "${fields[@]}"; fi
)

# Splice a standalone array reference into the pending env argv stream.
splice_env_array() {
    local marker_index reference name fact bounds first count subscript separator
    local unquoted=0
    local -a argv=()
    local array_reference='^\$\{([A-Za-z_][A-Za-z0-9_]*)\[([@*])\]\}$'
    [[ $literal =~ $env_expansion_ref && $literal == "${BASH_REMATCH[0]}" ]] || return 1
    marker_index=${BASH_REMATCH[1]}
    reference=${env_expansions[marker_index]}
    [[ $reference =~ $array_reference ]] || return 1
    name=${BASH_REMATCH[1]}
    subscript=${BASH_REMATCH[2]}
    fact=${variable_facts[$name]:-unknown}
    [[ $fact == array:* ]] || return 1
    bounds=${fact#array:}
    first=${bounds%:*}
    count=$(( ${bounds#*:} - first ))
    argv=("${known_array_words[@]:first:count}")
    if (( ! from_split )) && [[ $word == "$reference" ]]; then unquoted=1; fi
    if (( unquoted )) || [[ $subscript == '*' && $word == "\"$reference\"" ]]; then
        separator=${variable_facts[IFS]-scalar:$' \t\n'}
        [[ $separator == scalar:* ]] || return 1
        local IFS=${separator#scalar:}
        if (( unquoted )); then
            mapfile -d '' -t argv < <(split_credential_fields "$IFS" "$subscript" "${argv[@]}")
        else
            argv=("${argv[*]}")
        fi
    fi
    split_words=("${argv[@]}" "${split_words[@]:split_index}")
    split_index=0
    split_start=$start
}

split_env_words() {
    local input=$1 i char word='' quote='' active=0
    local variable='^\$\{[A-Za-z_][A-Za-z0-9_]*\}'
    local -a words=()
    for (( i=0; i<${#input}; i++ )); do
        char=${input:i:1}
        case $char in
            "'"|'"')
                if [[ $char == "$quote" ]]; then
                    quote=''
                elif [[ -z $quote ]]; then
                    quote=$char
                else
                    word+=$char
                fi
                ;;
            '#')
                if [[ -z $quote ]] && (( ! active )); then break; fi
                word+=$char
                ;;
            '$')
                if [[ $quote == "'" ]]; then
                    word+=$char
                elif [[ ${input:i} =~ $variable ]]; then
                    word+="$env_expansion_marker${#env_expansions[@]}$env_expansion_marker"
                    env_expansion_quotes[${#env_expansions[@]}]=$quote
                    env_expansions+=("${BASH_REMATCH[0]}")
                    i=$((i + ${#BASH_REMATCH[0]} - 1))
                else
                    return 1
                fi
                ;;
            '\')
                if [[ $quote == "'" && ${input:i+1:1} != [\\\'] ]]; then
                    word+=$char
                else
                    i=$((i + 1))
                    char=${input:i:1}
                    case $char in
                        c)
                            [[ -z $quote ]] || return 1
                            break
                            ;;
                        _)
                            if [[ -z $quote ]]; then
                                if (( active )); then words+=("$word"); fi
                                word=''
                                active=0
                                continue
                            fi
                            word+=' '
                            ;;
                        f|n|r|t|v)
                            char=\\$char
                            word+=${char@E}
                            ;;
                        [\\\'\"#\$]) word+=$char ;;
                        *) return 1 ;;
                    esac
                fi
                ;;
            [[:space:]])
                if [[ -z $quote ]]; then
                    if (( active )); then words+=("$word"); fi
                    word=''
                    active=0
                    continue
                fi
                word+=$char
                ;;
            *) word+=$char ;;
        esac
        active=1
    done
    [[ -z $quote ]] || return 1
    if (( active )); then words+=("$word"); fi
    split_words=("${words[@]}" "${split_words[@]:split_index}")
    split_index=0
}

declare -A tilde_accounts=()

resolves_credential_tilde() {
    local tilde_prefix='^~([^/:[:space:];&|<>()]*)([/:[:space:];&|<>()]|$)' account record
    if (( ${2:-1} == 0 )); then
        tilde_prefix='^~([^/[:space:];&|<>()]*)([/[:space:];&|<>()]|$)'
    fi
    [[ $1 =~ $tilde_prefix ]] || return 1
    account=${BASH_REMATCH[1]}
    case $account in
        ''|+|0|+0|-0) return 0 ;;
        -) [[ -n ${variable_facts[OLDPWD]:-} ]]; return ;;
        *[!A-Za-z0-9_.-]*) return 1 ;;
    esac
    if [[ ! ${tilde_accounts[$account]+present} ]]; then
        # A numeric getent key can look up a UID, so verify the login name.
        if record=$(getent -- passwd "$account" 2>/dev/null) && [[ ${record%%:*} == "$account" ]]; then
            tilde_accounts[$account]=1
        else
            tilde_accounts[$account]=0
        fi
    fi
    [[ ${tilde_accounts[$account]} == 1 ]]
}

credential_assignment_has_tilde() {
    [[ $1 == *'~'* ]] || return 1
    local source=$1 position=0 literal='' word_expansion=0 word_constructed=0
    read_shell_word '' 0 0 0 0 0 0 0 1
    (( word_constructed ))
}

# Array elements and select items can undergo pathname, tilde, and brace
# expansion. Keep quoted, escaped, and nested characters opaque.
list_word_has_literal() {
    local position=$1 literal='' word_expansion=0 word_glob=0 cursor=0
    local rhs=${source:$1:$2-$1} source=${source:0:$2}
    local word_constructed=0
    [[ -n $rhs ]] || return 1
    if [[ $rhs == @('<('|'>(')* && ${substitution_ends[$1]:-} == "$(($2 - 1))" ]]; then
        return 1
    fi
    read_shell_word '' 0 0 0 1 "$(( ! noglob && ! ${3:-0} ))" "$braceexpand" 0 "${4:-2}"
    (( ! word_glob && ! word_constructed )) && ! expansion_only
}

scan_credential_array() {
    local check_credentials=$1 start value_start rhs literal word_expansion indexed first_element=1
    local all_literal=1 word_glob word_constructed next_index=0 index
    local missing_zero=0 stored_array_fact
    local associative_array=${associative_declaration:-0} associative_form='' pair_pending=0 store_element=1
    local -a env_expansions=() env_expansion_quotes=()
    if [[ ${variable_facts[${assignment_name:-:}]:-} == assoc-array:* ]]; then associative_array=1; fi
    if (( associative_array )); then local -A array_words=()
    else local -a array_words=(); fi
    local literal_index='^\[(0|[1-9][0-9]{0,17})\]='
    local element_index='^\[[^]]*\]='
    array_fact='scalar:'
    while (( position < ${#source} )); do
        case ${source:position:1} in
            ')')
                position=$((position + 1))
                if (( all_literal )); then
                    if (( associative_array && pair_pending )); then array_words[$next_index]=''; fi
                    store_credential_array_fact array_words
                    array_fact=$stored_array_fact
                    if (( associative_array )); then array_fact=assoc-$array_fact; fi
                elif (( missing_zero )); then
                    array_fact=scalar:
                fi
                return
                ;;
            [[:space:]]) position=$((position + 1)); continue ;;
        esac
        start=$position
        literal=''
        word_expansion=0
        word_glob=0
        word_constructed=0
        read_shell_word '' 0 0 0 1 "$(( ! noglob ))" "$braceexpand" 0 2
        if (( position == start )); then
            position=$((position + 1))
            continue
        fi
        value_start=$start
        indexed=0
        rhs=${source:start:position-start}
        if [[ $rhs =~ $element_index ]]; then
            value_start=$((start + ${#BASH_REMATCH[0]}))
            indexed=1
        fi
        store_element=1
        if (( associative_array )) && [[ -z $associative_form ]]; then
            if (( indexed )); then associative_form=keyed; else associative_form=pairs; fi
        fi
        if (( associative_array )) && [[ $associative_form == pairs ]]; then
            local fact
            resolve_credential_assignment "$rhs"
            if [[ $fact == scalar:* ]]; then
                literal=${fact#scalar:}
                word_expansion=0 word_glob=0 word_constructed=0
            else all_literal=0; fi
            if (( ! pair_pending )); then
                next_index=$literal
                [[ -n $next_index ]] || all_literal=0
                pair_pending=1 store_element=0
            else pair_pending=0; fi
        elif (( indexed )); then
            if (( associative_array )); then
                local key_word=${rhs:1:value_start-start-3} key_fact
                resolve_credential_assignment "$key_word"
                key_fact=$fact
                if [[ $key_fact == scalar:?* ]]; then
                    next_index=${key_fact#scalar:}
                    position=$value_start
                    literal='' word_expansion=0 word_glob=0 word_constructed=0
                    env_expansions=() env_expansion_quotes=()
                    read_shell_word '' 0 2 0 0 0 0 0 0
                    if (( word_expansion )); then
                        if resolve_env_word 1; then word_expansion=0; fi
                    fi
                else all_literal=0; fi
            elif [[ $rhs =~ $literal_index ]]; then
                index=${BASH_REMATCH[1]}
                if (( first_element )); then missing_zero=$((index > 0)); fi
                if (( index == 0 )); then missing_zero=0; fi
                if (( all_literal )); then
                    next_index=$index
                    # The index brackets themselves are outside the element's glob.
                    position=$value_start
                    literal='' word_expansion=0 word_glob=0 word_constructed=0
                    read_shell_word '' 0 0 0 1 "$(( ! noglob ))" "$braceexpand" 0 2
                fi
            else
                all_literal=0
                missing_zero=0
            fi
        fi
        if (( associative_array && ! indexed )) && [[ $associative_form == keyed ]]; then all_literal=0; fi
        if (( word_expansion || word_glob || word_constructed )); then all_literal=0; fi
        if (( all_literal && store_element )); then
            array_words[$next_index]=$literal
            if (( ! associative_array )); then next_index=$((next_index + 1)); fi
        fi
        # Keep the element-zero literal for plain $name references when
        # later elements prevent recording the whole array.
        if (( first_element )); then
            first_element=0
            if (( word_expansion || indexed )) || [[ $rhs == *['*?[{~']* ]]; then
                array_fact=unknown
            else
                array_fact=scalar:$literal
            fi
        fi
        if (( check_credentials )) && list_word_has_literal "$value_start" "$position" "$indexed" "$((2 - indexed))"; then
            report_credential_assignment "$start"
        fi
    done
}

store_credential_array_fact() {
    local -n array_source=$1
    local element first=${#known_array_words[@]} bounds
    for element in "${!array_source[@]}"; do
        known_array_indices[${#known_array_words[@]}]=$element
        known_array_words+=("${array_source[$element]}")
    done
    bounds=$first:${#known_array_words[@]}
    if (( ${#array_source[@]} )) && [[ ! ${array_source[0]+present} ]]; then sparse_array_bounds[$bounds]=1; fi
    stored_array_fact=array:$bounds
}

set_credential_read_option() {
    case $1 in
        a) if [[ $2 =~ $identifier ]]; then read_array=$2; fi ;;
        n|N)
            if [[ $2 =~ ^[0-9]{1,9}$ ]]; then
                read_limit=$1:$2
            else
                read_limit=unknown
            fi
            ;;
        d) read_delimiter=$2; read_custom_delimiter=1 ;;
        t) if [[ $2 =~ ^(0+([.]0*)?|[.]0+)$ ]]; then read_poll=1; else read_poll=0; fi ;;
        u)
            read_descriptor=unknown read_other_fd=0
            if [[ $2 =~ ^[0-9]{1,8}$ ]]; then read_descriptor=$((10#$2)); fi
            ;;
    esac
}

# Resolve standalone list operands, retaining quoted @ cardinality for test.
# Uncertain unquoted splitting or globbing stays unknown.
resolve_credential_test_list_operand() {
    local source=$operand position=2 parameter_value=unknown pattern_list_known=0
    local quoted=0 separator
    local list_reference='^\$\{([@*]|[A-Za-z_][A-Za-z0-9_]*\[[@*]\])[@#%/^,]'
    local -a pattern_list_words=()
    if [[ $source == '"'*'"' ]]; then
        source=${source:1:-1}
        quoted=1
    fi
    [[ $source =~ $list_reference ]] || return 1
    read_parameter_word 0 0 0 1 || return 1
    (( pattern_list_known && position == ${#source} )) || return 1
    if (( quoted && ${#pattern_list_words[@]} != 1 )) && [[ $source == '${@'* || $source == *'[@]'* ]]; then
        test_bad_arity=1
        operand_list=1
        operand_words=("${pattern_list_words[@]}")
        literal=''
        return 0
    fi
    (( ${#pattern_list_words[@]} == 1 )) || return 1
    if (( ! quoted )); then
        separator=${variable_facts[IFS]-scalar:$' \t\n'}
        [[ $separator == scalar:* ]] || return 1
        mapfile -d '' -t pattern_list_words < <(
            split_credential_fields "${separator#scalar:}" scalar "${pattern_list_words[0]}"
        )
        (( ${#pattern_list_words[@]} == 1 )) || return 1
        [[ ${pattern_list_words[0]} != *[*?\[]* ]] || return 1
    fi
    literal=${pattern_list_words[0]}
}

resolve_credential_quoted_test_operand() {
    local part reference
    for part in "${!env_expansions[@]}"; do
        [[ ${env_expansion_quotes[part]:-} == '"' ]] || return 1
        reference=${env_expansions[part]}
        [[ $reference == '${#'* ||
           ( $reference != '$@' && $reference != '${@'* && $reference != *'[@]'* ) ]] || return 1
    done
    resolve_env_word 1
}

# Parse a scalar literal, variable reference, or known expansion without executing it.
parse_credential_test_operand() {
    local start=$position current operand_list=0 pattern_operand=0 case_pattern=''
    if [[ ${1:-0} == 1 && $form == '[[' ]]; then
        case $operator in
            ==|!=|=) pattern_operand=1 ;;
            '=~') pattern_operand=3 ;;
        esac
    fi
    local -a operand_words=() env_expansions=() env_expansion_quotes=()
    local reference='^"?\$([A-Za-z_][A-Za-z0-9_]*|\{[A-Za-z_][A-Za-z0-9_]*\}|[0-9#]|\{([0-9]+|#)\})"?$'
    literal='' word_expansion=0 word_glob=0 word_constructed=0
    if (( pattern_operand == 3 )); then regex_parts=() regex_quotes=(); fi
    read_shell_word '' 0 2 0 0 1 1 "$pattern_operand" 2
    operand=${source:start:position-start}
    if (( pattern_operand && position > start && ! word_constructed )); then
        if (( pattern_operand == 3 )); then
            local part
            test_operand=scalar:
            for part in "${!regex_parts[@]}"; do
                literal=${regex_parts[part]}
                resolve_env_word 1 1 || return 1
                regex_parts[part]=$literal
                test_operand+=$literal
            done
            comparison_pattern=2
        else
            resolve_credential_glob_pattern || return 1
            test_operand=scalar:$case_pattern
            comparison_pattern=1
        fi
    elif [[ $operand =~ $reference ]]; then
        test_operand=${BASH_REMATCH[1]}
        test_operand=${test_operand#\{}
        test_operand=${test_operand%\}}
        if [[ $test_operand =~ ^([0-9]+|#)$ ]]; then
            credential_special_parameter_fact "$test_operand"
            # A removed positional parameter expands to an empty test operand.
            if [[ $current == unset ]]; then current=scalar:; fi
            [[ $current == scalar:* ]] || return 1
            test_operand=$current
        fi
    elif (( position > start && ! word_constructed )) &&
            { (( ! word_glob )) || [[ $form == '[[' && $operator == @('=~'|-v) ]]; } &&
            { (( ! word_expansion )) ||
              { [[ $form == '[[' ]] && resolve_env_word 1 1; } ||
              { [[ $form != '[[' ]] &&
                { resolve_credential_test_list_operand || resolve_credential_quoted_test_operand; }; }; }; then
        test_operand=scalar:$literal
    else
        return 1
    fi
    if (( operand_list )); then
        test_argv+=("${operand_words[@]}")
    else
        current=$test_operand
        if [[ $current != scalar:* ]]; then credential_parameter_fact "$current" ''; fi
        if [[ $current == scalar:* ]]; then
            if (( word_expansion )) && [[ $operand != \"*\" &&
                    ( ${current#scalar:} == *[[:space:]*?\[]* || -z ${current#scalar:} ) ]]; then
                test_argv_known=0
            fi
            test_argv+=("${current#scalar:}")
        else
            test_argv_known=0
        fi
    fi
}

skip_credential_test_space() {
    while [[ ${source:position:1} == [[:space:]] ]]; do position=$((position + 1)); done
}

combine_credential_test_status() {
    local decisive=1
    if [[ $2 == '||' ]]; then decisive=0; fi
    if (( $1 == decisive || $3 == decisive )); then
        combined_status=$decisive
    elif (( $1 == 2 || $3 == 2 )); then
        combined_status=2
    else
        combined_status=$((1 - decisive))
    fi
}

# Use Bash's precedence inside [[ ]], keeping unknown facts distinct.
parse_credential_comparison() {
    local position=$1 literal='' word_expansion=0 word_glob=0 word_constructed=0
    local start form closer='' operand test_operand operator='' name mode expected status left_operand
    local comparison_pattern negated=0
    local -a group_conjunction=() group_disjunction=() group_negated=()
    local conjunction=0 disjunction=1 combined_status test_bad_arity=0 test_argv_known=1
    local -a test_argv=() regex_parts=() regex_quotes=()
    local end='^[[:blank:]]*(;|&&|\|\||$|'$'\n'')'
    local skip_expansion_effects=1 scan_word_substitutions=0
    local -a env_expansions=() env_expansion_quotes=() scanned_substitutions=() scanned_backticks=() skipped_arithmetic=()
    comparison_end=0
    skip_credential_test_space
    start=$position
    read_shell_word
    form=${source:start:position-start}
    [[ ! ${credential_functions[${form:-:}]:-} ]] || return 1
    case $form in
        '[[') closer=']]' ;;
        '[') closer=']' ;;
        test) ;;
        *) return 1 ;;
    esac
    while :; do
        comparison_pattern=0
        [[ ${source:position:1} == [[:space:]] ]] || return 1
        skip_credential_test_space
        negated=0
        if [[ $form != '[[' ]]; then
            while [[ ${source:position:1} == '!' && ${source:position+1:1} == [[:space:]] ]]; do
                test_argv+=('!')
                negated=$((1 - negated))
                position=$((position + 1))
                skip_credential_test_space
            done
        fi
        if [[ $form == '[[' ]]; then
            while :; do
                if [[ ${source:position:1} == '!' && ${source:position+1:1} == [[:space:]] ]]; then
                    negated=$((1 - negated))
                    position=$((position + 1))
                elif [[ ${source:position:1} == '(' ]]; then
                    group_conjunction+=("$conjunction") group_disjunction+=("$disjunction") group_negated+=("$negated")
                    conjunction=0 disjunction=1 negated=0
                    position=$((position + 1))
                else break; fi
                skip_credential_test_space
            done
        fi
        parse_credential_test_operand || return 1
        if [[ $operand == @(-n|-z|-v|-e|-f|-d|-s|-r|-w|-x) ]]; then
            operator=$operand
            [[ ${source:position:1} == [[:space:]] ]] || return 1
            skip_credential_test_space
            parse_credential_test_operand || return 1
            name=$test_operand expected=''
        else
            name=$test_operand left_operand=$operand
            [[ ${source:position:1} == [[:space:]] ]] || return 1
            skip_credential_test_space
            start=$position
            read_shell_word
            operator=${source:start:position-start}
            test_argv+=("$operator")
            [[ $operator == @(==|!=|=|=~|-lt|-le|-gt|-ge|-eq|-ne|-nt|-ot|-ef) ]] || return 1
            skip_credential_test_space
            parse_credential_test_operand 1 || return 1
            [[ $test_operand == scalar:* || $operator == '=~' || $operator == -@(lt|le|gt|ge|eq|ne|nt|ot|ef) ]] || return 1
            if [[ $operator == '=~' ]]; then
                [[ $form == '[[' ]] || return 1
                comparison_pattern=2
                if [[ $test_operand != scalar:* ]]; then
                    local current
                    credential_parameter_fact "$test_operand" ''
                    test_operand=$current
                    [[ $test_operand == scalar:* ]] || return 1
                fi
            fi
            expected=$test_operand
            operand=$left_operand
        fi
        mode=0
        if [[ $form == '[[' ]]; then
            mode=2
        elif [[ $name != scalar:* && $operand != '"'* ]]; then
            mode=1
        fi
        status=0
        credential_comparison_value "$name" "$operator" "$expected" "$mode" "$comparison_pattern" || status=$?
        if (( negated && status < 2 )); then status=$((1 - status)); fi
        combine_credential_test_status "$conjunction" '&&' "$status"
        conjunction=$combined_status
        if [[ -n $closer ]]; then
            [[ ${source:position:1} == [[:space:]] ||
               ( $form == '[[' && ${source:position:1} == ')' ) ]] || return 1
            skip_credential_test_space
            while [[ $form == '[[' && ${source:position:1} == ')' ]]; do
                (( ${#group_negated[@]} )) || return 1
                combine_credential_test_status "$disjunction" '||' "$conjunction"
                status=$combined_status
                if (( group_negated[-1] && status < 2 )); then status=$((1 - status)); fi
                conjunction=${group_conjunction[-1]} disjunction=${group_disjunction[-1]}
                unset 'group_conjunction[-1]' 'group_disjunction[-1]' 'group_negated[-1]'
                combine_credential_test_status "$conjunction" '&&' "$status"
                conjunction=$combined_status
                position=$((position + 1))
                skip_credential_test_space
            done
            if [[ $form == '[[' ]]; then
                case ${source:position:2} in
                    '&&') position=$((position + 2)); continue ;;
                    '||')
                        combine_credential_test_status "$disjunction" '||' "$conjunction"
                        disjunction=$combined_status conjunction=0
                        position=$((position + 2))
                        continue
                        ;;
                esac
            fi
            [[ ${source:position:${#closer}} == "$closer" ]] || return 1
            position=$((position + ${#closer}))
        fi
        break
    done
    (( ! ${#group_negated[@]} )) || return 1
    [[ ${source:position} =~ $end ]] || return 1
    combine_credential_test_status "$disjunction" '||' "$conjunction"
    comparison_status=$combined_status
    if (( test_bad_arity )); then
        comparison_status=2
        # Bash test can reinterpret some short argv lists (notably a leading
        # !). Evaluate known words as data, excluding variable-lookup operators.
        local argument safe_test=1
        for argument in "${test_argv[@]}"; do
            if [[ $argument == @(-v|-R|-e|-f|-d|-s|-r|-w|-x|-a|-b|-c|-h|-L|-p|-S|-t|-O|-G|-N|-nt|-ot|-ef) ]]; then safe_test=0; fi
        done
        if (( test_argv_known && safe_test )); then
            comparison_status=0
            builtin test "${test_argv[@]}" 2>/dev/null || comparison_status=1
        fi
    fi
    comparison_end=$position
}

# Only fixed syntax and generated integer indices enter eval. Regex payloads
# remain array data, expanded once by Bash; source text is never evaluated.
credential_regex_matches() (
    local value=$1 program='[[ $value =~ ' part
    if (( ! ${#regex_parts[@]} )); then program+='""'; fi
    for part in "${!regex_parts[@]}"; do
        if [[ ${regex_quotes[part]} == 1 ]]; then program+='"'; fi
        program+='${regex_parts['$part']}'
        if [[ ${regex_quotes[part]} == 1 ]]; then program+='"'; fi
    done
    program+=' ]]'
    if (( nocasematch )); then shopt -s nocasematch; fi
    builtin eval "$program"
)

credential_comparison_value() {
    local fact=$1 value
    if [[ $2 == -v ]]; then
        local current target=${fact#scalar:} name index=''
        [[ $fact == scalar:* ]] || return 2
        if [[ $target =~ $assignment_target$ ]]; then
            name=${BASH_REMATCH[1]} index=${BASH_REMATCH[2]}
        elif [[ $target =~ ^[0-9]+$ ]]; then name=$target
        else return 2
        fi
        credential_parameter_fact "$name" "$index"
        case $current in
            unset) return 1 ;;
            unknown|array-unknown) return 2 ;;
            *) return 0 ;;
        esac
    fi
    # A malformed known regex fails even when the subject is inherited.
    if (( ${5:-0} == 2 )); then
        local regex_status=0
        credential_regex_matches '' || regex_status=$?
        (( regex_status != 2 )) || return 1
    fi
    local name_pattern='^[A-Za-z_][A-Za-z0-9_]*$'
    if (( $4 == 2 )) && [[ $2 == -@(lt|le|gt|ge|eq|ne) && $fact == scalar:* &&
            ${fact#scalar:} =~ $name_pattern ]]; then
        fact=${fact#scalar:}
    fi
    if [[ $fact != scalar:* ]]; then
        local current
        credential_parameter_fact "$fact" ''
        fact=$current
    fi
    if [[ $fact == unset ]]; then fact=scalar:; fi
    [[ $fact == scalar:* ]] || return 2
    value=${fact#scalar:}
    if (( $4 == 1 )) && [[ -z $value || $value == *[[:space:]*?\[]* ||
            ${variable_facts[IFS]-scalar:$' \t\n'} != "scalar:"$' \t\n' ]]; then
        return 2
    fi
    if [[ $2 == @(-e|-f|-d|-s|-r|-w|-x) ]]; then
        credential_file_test "$2" "$value"
        return
    fi
    local expected=$3
    if [[ $2 == @(-nt|-ot|-ef) ]]; then
        if [[ $expected != scalar:* ]]; then expected=${variable_facts[$expected]:-}; fi
        [[ $expected == scalar:* ]] || return 2
        expected=${expected#scalar:}
        local left_file right_file
        left_file=$(credential_file_path "$value") || return 2
        right_file=$(credential_file_path "$expected") || return 2
        # Journal order cannot establish timestamps for different files.
        if [[ $left_file != "$right_file" && $2 != -ef ]]; then return 2; fi
        # Both spellings must resolve to an existing modeled file. A spelling
        # such as regular-file/ can be absent despite normalizing identically;
        # -nt/-ot have asymmetric missing-operand rules, so keep that unknown.
        credential_file_test -e "$value" || return 2
        credential_file_test -e "$expected" || return 2
        if [[ $2 == -ef && $left_file != "$right_file" ]]; then
            # Existing tree entries have inspectable identities, including
            # hard links. Distinct journal-created paths have distinct identities.
            if [[ -e $left_file && -e $right_file ]]; then
                [[ $left_file -ef $right_file ]]
                return
            fi
            return 1
        fi
        [[ $2 == -ef ]]
        return
    fi
    if [[ $2 == -@(lt|le|gt|ge|eq|ne) ]]; then
        if (( $4 == 2 )) && [[ $expected == scalar:* && ${expected#scalar:} =~ $name_pattern ]]; then
            expected=${expected#scalar:}
        fi
        if [[ $expected != scalar:* ]]; then expected=${variable_facts[$expected]:-}; fi
        [[ $expected == scalar:* ]] || return 2
        expected=${expected#scalar:}
        local integer='^[+-]?[0-9]+$'
        if (( $4 == 2 )); then
            integer='^[+-]?(0[xX][[:xdigit:]]+|[0-9]+)$'
        fi
        [[ $value =~ $integer && $expected =~ $integer ]] || return 2
        if (( $4 == 2 )); then
            value=$(credential_arithmetic_value "$value") || return 2
            expected=$(credential_arithmetic_value "$expected") || return 2
        fi
        builtin test "$value" "$2" "$expected" 2>/dev/null
        return
    fi
    expected=${expected#scalar:}
    if (( ${5:-0} == 2 )); then
        credential_regex_matches "$value" || return 1
        return 0
    elif (( ${5:-0} == 3 )); then
        ( if (( nocasematch )); then shopt -s nocasematch; fi; [[ $value =~ "$expected" ]] )
        return
    fi
    if (( ${5:-0} )); then
        if [[ $2 == '!=' ]]; then
            ! credential_case_matches "$value" "$expected"
        else
            credential_case_matches "$value" "$expected"
        fi
        return
    fi
    if (( $4 == 2 && nocasematch )); then value=${value,,}; expected=${expected,,}; fi
    case $2 in
        -n) [[ -n $value ]] ;;
        -z) [[ -z $value ]] ;;
        '!=') [[ $value != "$expected" ]] ;;
        *) [[ $value == "$expected" ]] ;;
    esac
}

# Shell-level && and || have equal precedence and associate left to right.
evaluate_credential_loop_condition() {
    local source=$1 position=0 operator comparison_end comparison_status combined_status
    loop_status=$2
    skip_credential_test_space
    while [[ ${source:position:2} == @('&&'|'||') ]]; do
        operator=${source:position:2}
        parse_credential_comparison "$((position + 2))" || return 1
        combine_credential_test_status "$loop_status" "$operator" "$comparison_status"
        loop_status=$combined_status
        position=$comparison_end
        skip_credential_test_space
    done
    [[ ${source:position:1} == @(''|';') ]] || return 1
    loop_condition_end=$position
}

# Locate a condition's read after a chain of side-effect-free tests.
find_credential_condition_read() {
    local position=$1 comparison_end comparison_status
    local read_word='^read([[:space:];&|<>]|$)'
    while parse_credential_comparison "$position"; do
        position=$comparison_end
        skip_credential_test_space
        [[ ${source:position:2} == @('&&'|'||') ]] || return 1
        condition_read_connector=$position
        position=$((position + 2))
        skip_credential_test_space
        if [[ ${source:position} =~ $read_word ]]; then
            condition_read_start=$position
            return 0
        fi
    done
    return 1
}

evaluate_credential_loop_prefix() {
    local source=${loop_prefix_tests[$1]:-} comparison_end comparison_status loop_condition_end
    loop_prefix_status=0
    loop_skip_read=0
    [[ -n $source ]] || return 0
    parse_credential_comparison 0 || return 1
    evaluate_credential_loop_condition "${source:comparison_end}" "$comparison_status" || return 1
    loop_prefix_status=$loop_status
    if [[ $loop_prefix_status:${loop_prefix_operator[$1]} == @('0:||'|'1:&&') ]]; then loop_skip_read=1; fi
}

apply_credential_read() {
    local input_source scope=${isolated_scopes[-1]} effect name condition_read=0 condition_command=0 had_input=0
    local separator=${variable_facts[IFS]-scalar:$' \t\n'}
    local index loop_status loop_condition_end loop_prefix_status=0 loop_skip_read=0 condition_start=$position
    for index in "${!pending_names[@]}"; do
        if [[ ${pending_names[index]} == IFS ]]; then separator=${pending_facts[index]}; fi
    done
    (( ! dead_branches )) || return 0
    credential_input_source
    if (( ${#if_conditions[@]} && ${#credential_scopes[@]} )) &&
            [[ ${credential_scopes[-1]} == @(if|loop) &&
               ${condition_kind[-1]} == @(if|while|until) &&
               ${if_conditions[-1]} == waiting-@(unknown|read) ]] &&
            (( ! conditional_chain && ! read_poll && ! read_other_fd )); then
        if [[ ${condition_kind[-1]} == @(while|until) && ${if_conditions[-1]} == waiting-read &&
                ( -z $command_input || $input_source != unknown ) ]] && evaluate_credential_loop_condition "${source:position}" 2; then
            condition_read=1
            loop_tests[$scope]=${source:position:loop_condition_end}
            loop_until[$scope]=0
            if [[ ${condition_kind[-1]} == until ]]; then loop_until[$scope]=1; fi
        elif [[ ${source:position:2} != @('&&'|'||') ]]; then
            condition_command=1
        fi
    fi
    if (( condition_read )) || { (( ! replaying_function )) && records_credential_effects "$scope"; }; then
        credential_input_source 1
        record_credential_function_effect "$scope" read "$input_source" "" 0
        effect=$next_function_effect
        function_read_options[effect]="$read_raw $read_poll $read_other_fd $read_custom_delimiter ${read_limit:-none}"
        function_read_arrays[effect]=$read_array
        for index in "${!pending_names[@]}"; do
            if [[ ${pending_names[index]} == IFS ]]; then
                function_read_ifs[effect]=${pending_facts[index]}
            fi
        done
        function_read_delimiters[effect]=$read_delimiter
        for name in "${read_names[@]}"; do
            function_read_names[$effect:${function_read_counts[effect]:-0}]=$name
            function_read_counts[effect]=$(( ${function_read_counts[effect]:-0} + 1 ))
        done
        credential_input_source
        if (( condition_read )); then
            loop_condition_reads[$scope]=$effect
        fi
    fi
    local read_status=-1
    if (( condition_read )) && [[ $input_source == stream:* ]]; then
        local input=${credential_streams[${input_source#stream:}]}
        if [[ -n $input ]]; then had_input=1; fi
    fi
    if (( condition_read )); then evaluate_credential_loop_prefix "$scope" || loop_prefix_status=2; fi
    if (( ! loop_skip_read )); then evaluate_credential_read "$input_source"; fi
    builtin_status=$((read_status < 0 ? 2 : read_status))
    if (( condition_read )); then
        if [[ -n ${loop_prefix_tests[$scope]:-} ]]; then
            local combined_status
            combine_credential_test_status "$loop_prefix_status" "${loop_prefix_operator[$scope]}" "$((read_status < 0 ? 2 : read_status))"
            loop_status=$combined_status
        else
            loop_status=$((read_status < 0 ? 2 : read_status))
        fi
        evaluate_credential_loop_condition "${loop_tests[$scope]}" "$loop_status"
        builtin_status=$loop_status
        if_conditions[-1]=waiting-sentinel
        if [[ $input_source == stream:* ]] && (( read_status >= 0 || loop_skip_read )); then
            loop_streams[$scope]=$input_source
            if (( ! had_input && ! loop_skip_read && read_status == 1 )); then loop_eof[$scope]=1; fi
        fi
        if [[ $input_source != stream:* ]] && (( read_status >= 0 )); then
            # A redirection on the condition opens fresh input each time.
            loop_streams[$scope]=$input_source
        fi
        case $loop_status in
            0) if_conditions[-1]=waiting-true ;;
            1) if_conditions[-1]=waiting-false ;;
        esac
        position=$((condition_start + loop_condition_end))
    elif (( condition_command && read_status >= 0 )); then
        if (( read_status )); then if_conditions[-1]=waiting-false; else if_conditions[-1]=waiting-true; fi
    fi
}

# Re-evaluate the complete condition so every executed element applies its
# effects, including the final failing evaluation. Use the same status handling
# as the first pass: negation changes status only, and AND-OR skips retain effects
# from the elements that did run. Keep parser control state local to this pass.
replay_credential_loop_condition() {
    local scope=$1
    local -a if_conditions=("${if_conditions[@]}") if_negated=("${if_negated[@]}")
    local command_negated=0 builtin_skip=0 readonly_chain_skip=0
    local -A builtin_chain_operator=() builtin_chain_status=()
    if_conditions[-1]=waiting-command
    if_negated[-1]=0
    scan_credential_range "${loop_condition_starts[$scope]}" "${loop_condition_ends[$scope]}"
    case ${if_conditions[-1]} in
        waiting-true) loop_status=0 ;;
        waiting-false) loop_status=1 ;;
        *) loop_status=2 ;;
    esac
}

replay_credential_loop_body() {
    local scope=${isolated_scopes[-1]} item=1
    local count=${loop_list_count[$scope]:-0} input_source=${loop_streams[$scope]:-}
    local loop_status comparison_source=${loop_comparisons[$scope]:-}
    local counter_limit=256
    # Preserve complete consumption of known finite streams, even when their
    # byte count exceeds the general bound for reopened or unknown inputs.
    # Count the first evaluation already performed and the final EOF read.
    if [[ $input_source == stream:* ]]; then
        local stream=${input_source#stream:}
        if (( ${#credential_streams[stream]} + 2 > counter_limit )); then
            counter_limit=$((${#credential_streams[stream]} + 2))
        fi
    fi
    local outer_iterations=$for_iteration_path
    local for_iteration_path=$outer_iterations
    if (( discovering_credential_inputs )) ||
            [[ ! ${loop_body_sites[$scope]+present} ]]; then return 0; fi
    while [[ ! ${loop_has_break[$scope]+present} ]]; do
        if [[ ${loop_has_continue[$scope]+present} ]]; then
            unset 'loop_has_continue[$scope]'
            dead_branches=$((dead_branches - 1))
        fi
        if (( dead_branches )) || [[ ${loop_eof[$scope]:-0} == 1 ]]; then break; fi
        item=$((item + 1))
        if [[ ${loop_arithmetic_conditions[$scope]+present} ]]; then
            if (( item > counter_limit )); then scope_stdout[$scope]=unknown; break; fi
            apply_credential_arithmetic_assignment "${loop_arithmetic_steps[$scope]}"
            if (( arithmetic_assignment_failed )); then break; fi
            apply_credential_arithmetic_assignment "${loop_arithmetic_conditions[$scope]}"
            if (( arithmetic_assignment_failed )) || [[ -z $arithmetic_assignment_result ]]; then break; fi
            if (( arithmetic_assignment_result == 0 )); then break; fi
        elif [[ ${loop_condition_commands[$scope]+present} || -n $comparison_source || -n $input_source ]]; then
            if (( item > counter_limit )); then
                scope_stdout[$scope]=unknown
                # Bounded replay cannot establish the eventual positional state.
                if (( ${loop_condition_commands[$scope]:-0} )); then
                    change_credential_state positional_bounds ''
                    change_credential_state positional_empty 0
                fi
                break
            fi
            replay_credential_loop_condition "$scope"
            if (( loop_status == 2 )); then scope_stdout[$scope]=unknown; break; fi
            if (( loop_status != loop_until[$scope] )); then break; fi
        else
            if (( item > count )); then break; fi
            change_credential_state "fact:${loop_names[$scope]}" "${loop_list_items[$scope:$item]}"
        fi
        for_iteration_path=$outer_iterations/${1%:*}:${1#*:}:$item
        scan_credential_range "${loop_body_sites[$scope]}" "$1"
    done
}

finish_credential_loop() {
    local scope=${isolated_scopes[-1]}
    # Replay against the call site's input and facts instead of recording
    # the definition-time pass as a fixed sequence of writes.
    if (( ! replaying_function && ! discovering_credential_inputs && ! dead_branches )) &&
            [[ ${loop_condition_reads[$scope]+present} || ${loop_comparisons[$scope]+present} || ${loop_condition_commands[$scope]+present} ||
               ${loop_arithmetic_conditions[$scope]+present} || ${loop_uses_positionals[$scope]+present} || ${loop_list_count[$scope]:-0} -gt 0 ]] &&
            records_credential_effects "$scope"; then
        function_writes[$scope]=''
        record_credential_function_effect "$scope" loop "${scope_sites[$scope]}" "$logical_start:$position" 0
        conditional_function_returns[$function_depth]=1
    fi
}

# Fatal evaluation in a condition skips both arms/the loop body. Preserve
# the mark across else/elif, and consume it when the owning construct closes.
abort_credential_condition() {
    (( ! dead_branches && ${#if_conditions[@]} )) || return 1
    [[ ${if_conditions[-1]} == waiting-* && ${condition_kind[-1]} == @(if|while|until) ]] || return 1
    local scope=${isolated_scopes[-1]} depth=${#credential_scopes[@]} owner
    # Braces share the condition's shell. A subshell/function boundary owns
    # its own failure and must not abort a condition in its caller.
    for (( owner=depth-1; owner>=0; owner-- )); do
        case ${credential_scopes[owner]} in
            if|loop) scope=${isolated_scopes[owner+1]}; break ;;
            paren|function-*) return 1 ;;
        esac
    done
    (( owner >= 0 )) || return 1
    for (( ; depth>owner; depth-- )); do
        unset 'builtin_chain_operator[$depth]' 'builtin_chain_status[$depth]'
    done
    aborted_credential_shells[$scope]=1
    readonly_abort_scopes[$scope]=1
    dead_branches=$((dead_branches + 1))
}

# Stop the current shell when the fatal builtin is reached on a known path.
# Subshells and detached compounds have their own execution boundary.
abort_credential_shell() {
    local site=$1 index scope boundary=0 arm
    (( ! dead_branches )) || return 0
    if abort_credential_condition; then return 0; fi
    for (( index=-1; -index<=${#credential_scopes[@]}; index-- )); do
        scope=${isolated_scopes[index]}
        if (( ${compound_scopes[$scope]:-0} )) ||
                [[ -n ${scope_sites[$scope]:-} && ${detached_compound_sites[${scope_sites[$scope]}]+present} ]]; then
            boundary=$scope
            break
        fi
        case ${credential_scopes[index]} in
            loop)
                if [[ ! ${certain_loop_bodies[$scope]+present} ]]; then boundary=$scope; break; fi
                ;;
            if)
                if [[ ${certain_if_arms[$scope]:-0} != 1 ]]; then boundary=$scope; break; fi
                ;;
            case)
                arm=${return_case_indices[$scope]}
                if (( ! case_known[arm] || ! case_certain[arm] || case_prior_possible[arm] )); then
                    boundary=$scope
                    break
                fi
                ;;
            paren|function-*) boundary=$scope; break ;;
        esac
    done
    if (( diagnostic_function && boundary == diagnostic_function )); then
        printf 'abort %s\n' "$site"
    fi
    aborted_credential_shells[$boundary]=1
    dead_branches=$((dead_branches + 1))
}

# A readonly assignment unwinds the compound chain in this shell. Keep
# these marks until the constructs close, including across else/case arms.
abort_credential_readonly() {
    local index scope
    (( ! dead_branches )) || return 0
    for (( index=-1; -index<=${#credential_scopes[@]}; index-- )); do
        scope=${isolated_scopes[index]}
        aborted_credential_shells[$scope]=1
        readonly_abort_scopes[$scope]=1
        dead_branches=$((dead_branches + 1))
        if (( diagnostic_function && scope == diagnostic_function )); then
            printf 'readonly-abort %s\n' "$scope"
        fi
        if [[ ${credential_scopes[index]} == paren || ${credential_scopes[index]} == function-* ]] ||
                (( ${compound_scopes[$scope]:-0} )) ||
                [[ -n ${scope_sites[$scope]:-} && ${detached_compound_sites[${scope_sites[$scope]}]+present} ]]; then
            break
        fi
    done
    if (( ! ${#credential_scopes[@]} && ${credential_subshell_context:-0} )); then
        aborted_credential_shells[0]=1
        dead_branches=$((dead_branches + 1))
    fi
}

clear_credential_abort() {
    local scope=$1
    if [[ ${readonly_abort_scopes[$scope]+present} && ${2:-0} != 1 ]]; then return 0; fi
    unset 'readonly_abort_scopes[$scope]'
    if [[ ${aborted_credential_shells[$scope]+present} ]]; then
        unset 'aborted_credential_shells[$scope]'
        dead_branches=$((dead_branches - 1))
    fi
}

control_credential_loop() {
    local action=$1 level=${2:-1} site=$3 index scope arm target='' expand=1
    (( ! dead_branches )) || return 0
    # Outside a loop Bash diagnoses the context before reading the operand.
    for (( index=-1; -index<=${#credential_scopes[@]}; index-- )); do
        scope=${isolated_scopes[index]}
        if (( ${compound_scopes[$scope]:-0} )) ||
                [[ -n ${scope_sites[$scope]:-} && ${detached_compound_sites[${scope_sites[$scope]}]+present} ]]; then return 0; fi
        case ${credential_scopes[index]} in
            loop) break ;;
            function-*|paren) return 0 ;;
        esac
    done
    (( -index <= ${#credential_scopes[@]} )) || return 0
    case $level in
        \'*\') expand=0 ;;
    esac
    local integer='^[[:space:]]*([+-]?)([0-9]+)[[:space:]]*$'
    if (( function_depth && ! replaying_function )) &&
            { (( expand )) && [[ $level == '$'* ]] || [[ ! $level =~ $integer ]]; }; then
        record_credential_function_effect "${isolated_scopes[-1]}" conditional-abort "$site" '' 0
        conditional_function_returns[$function_depth]=1
        function_may_abort[$function_depth]=1
    fi
    # Resolve the complete word before classifying its numeric value. Each
    # arithmetic expansion uses the restricted evaluator; literals stay intact.
    local source=$level position=0 literal='' word_expansion=0
    local capture_loop_expansions=1 delimiter_quoted=0
    local -a env_expansions=() env_expansion_quotes=() loop_unquoted_expansions=()
    read_shell_word '' 0 2
    (( position == ${#source} )) || return 0
    local marked=$literal expanded marker
    if (( word_expansion )); then
        resolve_env_word 1 0 1 || return 0
    fi
    level=$literal
    if (( ${#loop_unquoted_expansions[@]} )); then
        local separator=${variable_facts[IFS]-unset}
        case $separator in
            unset) separator=$' \t\n' ;;
            scalar:*) separator=${separator#scalar:} ;;
            *) return 0 ;;
        esac
        # Only unquoted expansion results undergo field splitting. Literal
        # suffixes and quoted parts must not become IFS delimiters.
        for index in "${loop_unquoted_expansions[@]}"; do
            marker=$env_expansion_marker$index$env_expansion_marker
            literal=$marker
            resolve_env_word 1 0 1 || return 0
            expanded=$literal
            if [[ -n $separator && $expanded == *["$separator"]* ]]; then
                # Preserve the established single-expansion splitter. Mixed
                # words requiring splitting remain unknown rather than
                # incorrectly diagnosing a fatal numeric operand.
                [[ $marked == "$marker" && $delimiter_quoted == 0 ]] || return 0
                local -a fields=()
                mapfile -d '' -t fields < <(split_credential_fields "$separator" scalar "$expanded")
                case ${#fields[@]} in
                    0) level=1 ;;
                    1) level=${fields[0]} ;;
                    *) return 0 ;;
                esac
            fi
            if (( ! noglob )) && [[ $expanded == *[*?\[]* ]]; then return 0; fi
        done
        if [[ -z $level && $delimiter_quoted == 0 ]]; then level=1; fi
    fi
    case $level in
        --) level=1 ;;
        --help) return 0 ;;
    esac
    if [[ ! $level =~ $integer ]]; then
        if (( ! function_depth || replaying_function )); then abort_credential_shell "$site"; fi
        return 0
    fi
    local sign=${BASH_REMATCH[1]}
    level=${BASH_REMATCH[2]}
    # Nonpositive levels exit all loops, even for continue. Oversized
    # positive levels retain the requested action at the outermost loop.
    level=${level#"${level%%[!0]*}"}
    if [[ $sign == '-' || -z $level ]]; then
        action=break
        level=${#credential_scopes[@]}
    elif (( ${#level} > 9 )); then
        level=${#credential_scopes[@]}
    fi
    for (( index=-1; -index<=${#credential_scopes[@]}; index-- )); do
        scope=${isolated_scopes[index]}
        if (( ${compound_scopes[$scope]:-0} )) ||
                [[ -n ${scope_sites[$scope]:-} && ${detached_compound_sites[${scope_sites[$scope]}]+present} ]]; then break; fi
        case ${credential_scopes[index]} in
            loop)
                target=$scope
                level=$((level - 1))
                if (( ! level )); then break; fi
                ;;
            if) [[ ${certain_if_arms[$scope]:-0} == 1 ]] || break ;;
            case)
                arm=${return_case_indices[$scope]}
                (( case_known[arm] && case_certain[arm] && ! case_prior_possible[arm] )) || break
                ;;
            function-*|paren) break ;;
        esac
    done
    if [[ -n $target && ${loop_body_sites[$target]+present} ]]; then
        if [[ $action == break ]]; then
            loop_has_break[$target]=1
        else
            loop_has_continue[$target]=1
        fi
        dead_branches=$((dead_branches + 1))
    fi
}

replay_credential_read() {
    local effect=$1 input_source=$2 separator=${variable_facts[IFS]-scalar:$' \t\n'}
    local read_raw read_poll read_other_fd read_custom_delimiter read_limit read_array index
    local read_delimiter=${function_read_delimiters[$1]}
    local -a read_names=()
    IFS=' ' read -r read_raw read_poll read_other_fd read_custom_delimiter read_limit <<< "${function_read_options[effect]}"
    separator=${function_read_ifs[effect]-$separator}
    read_array=${function_read_arrays[effect]}
    if [[ $read_limit == none ]]; then read_limit=''; fi
    for (( index=0; index<${function_read_counts[effect]:-0}; index++ )); do
        read_names+=("${function_read_names[$effect:$index]}")
    done
    evaluate_credential_read "$input_source"
}

# Resolve an input file where the redirection opens, before a callee can cd.
bind_credential_input() {
    local file
    [[ $input_source == path:* ]] || return 0
    file=$(credential_file_path "${input_source#path:}") || file=''
    if [[ -n $file ]]; then input_source=file:$file; else input_source=unknown; fi
}

# Literal removals use the same scope and function journal as file output.
# realpath -m alone would turn regular-file/../other into a valid path.
# Validate each traversed directory against the tree and journal first.
credential_file_traversal() {
    local raw=$1 prefix canonical id cursor created
    if [[ $raw != /* ]]; then
        [[ -n $credential_directory ]] || return 2
        raw=$credential_directory/$raw
    fi
    for (( cursor=1; cursor<${#raw}; cursor++ )); do
        [[ ${raw:cursor:1} == / ]] || continue
        prefix=${raw:0:cursor}
        canonical=$(credential_file_path "$prefix") || return 2
        [[ $canonical == "$scan_root" || $canonical == "$scan_root/"* ]] || continue
        id=${credential_file_ids[$canonical]:-}
        if [[ -n $id && ${credential_file_contents[$id]+present} ]]; then
            case ${credential_file_contents[$id]} in
                directory) continue ;;
                maybe-absent) return 2 ;;
                *) return 1 ;;
            esac
        fi
        [[ ! -d $canonical ]] || continue
        if [[ -e $canonical || -L $canonical ]]; then return 1; fi
        while IFS= read -r created; do
            [[ -n $created ]] || continue
            if [[ $created == '*' || $canonical == "$created" || $canonical == "$created/"* || $created == "$canonical/"* ]]; then return 2; fi
        done <<< "$credential_created_directories"
        return 1
    done
}

# Delay definite mkdir facts until all arguments/options have been parsed.
apply_credential_mkdir() {
    local operand options=1 file parent
    for operand in "${output_words[@]}"; do
        if [[ $operand == -- ]]; then break; fi
        if [[ $operand == -* && $operand != -p ]]; then
            change_credential_state file:uncertain 1
            return
        fi
    done
    for operand in "${output_words[@]}"; do
        if (( options )) && [[ $operand == -- ]]; then options=0; continue; fi
        if (( options )) && [[ $operand == -* ]]; then continue; fi
        credential_file_traversal "$operand" || continue
        file=$(credential_file_path "$operand") || continue
        parent=${file%/*}
        if [[ $file == "$scan_root/"* && ! -e $file && ! -L $file &&
                -d $parent && -w $parent && -x $parent ]]; then
            apply_credential_output "$operand" directory
        fi
    done
}

apply_credential_remove() {
    local operand file options=1 supported=1 id parent fact
    # GNU rm accepts options after operands too. Inspect the whole argv before
    # claiming a removal, so interactive/recursive options remain conservative.
    for operand in "${output_words[@]}"; do
        if [[ $operand == -- ]]; then break; fi
        if [[ $operand == -* && $operand != -f ]]; then supported=0; fi
    done
    if (( ! supported )); then change_credential_state file:uncertain 1; return; fi
    for operand in "${output_words[@]}"; do
        if (( options )) && [[ $operand == -- ]]; then options=0; continue; fi
        if (( options )) && [[ $operand == -* ]]; then continue; fi
        local traversal_status=0
        credential_file_traversal "$operand" || traversal_status=$?
        if (( traversal_status == 1 )); then continue; fi
        if (( traversal_status == 2 )); then change_credential_state file:uncertain 1; continue; fi
        file=$(credential_file_path "$operand") || continue
        parent=${file%/*}
        id=${credential_file_ids[$file]:-}
        if [[ -n $id && ${credential_file_contents[$id]:-} == directory ]] && (( supported )); then continue; fi
        # realpath follows links; removing a link must not delete its referent
        # in the model. Keep its existence unknown instead.
        local lexical=$operand
        if [[ $lexical != /* ]]; then lexical=$credential_directory/$lexical; fi
        fact=maybe-absent
        # Do not infer successful removal of directories, protected files or
        # paths outside the tree from the scanner host's unrelated state.
        if (( supported )) && [[ $file == "$scan_root/"* && ! -d $file && ! -L $lexical &&
                -d $parent && -w $parent && -x $parent ]] &&
                { [[ ! -k $parent || -O $parent || -O $file ]]; }; then fact=absent; fi
        apply_credential_output "$operand" "$fact"
    done
}

# Consult journaled output before the scanned tree. Paths outside that tree,
# uncertain creations and permission bits of new files remain unknown.
credential_file_test() {
    local primary=$1 operand=$2 file id created contents
    [[ -n $operand ]] || return 1
    [[ ! ${credential_file_contents[uncertain]+present} ]] || return 2
    credential_file_traversal "$operand" || return $?
    file=$(credential_file_path "$operand") || return 2
    id=${credential_file_ids[$file]:-}
    if [[ -n $id && ${credential_file_contents[$id]+present} ]]; then
        contents=${credential_file_contents[$id]}
        case $contents in
            absent) return 1 ;;
            maybe-absent) return 2 ;;
            directory)
                case $primary in
                    -e|-d) return 0 ;;
                    -f) return 1 ;;
                    *) return 2 ;;
                esac ;;
        esac
        [[ $operand != */ ]] || return 1
        case $primary in
            -e|-f) return 0 ;;
            -d) return 1 ;;
            -s)
                [[ $contents == literal:* ]] || return 2
                [[ -n ${contents#literal:} ]]; return ;;
            *) return 2 ;;
        esac
    fi
    [[ $file == "$scan_root" || $file == "$scan_root/"* ]] || return 2
    while IFS= read -r created; do
        [[ -n $created ]] || continue
        if [[ $created == '*' || $file == "$created" || $file == "$created/"* || $created == "$file/"* ]]; then
            return 2
        fi
    done <<< "$credential_created_directories"
    builtin test "$primary" "$file"
}

credential_file_content() {
    local file=$1 id=${credential_file_ids[$1]:-} bytes
    if [[ $file == /dev/null ]]; then
        contents=literal:
    elif [[ -n $id && ${credential_file_contents[$id]+present} ]]; then
        contents=${credential_file_contents[$id]}
    elif [[ $file == "${scan_root%/}/"* && -f $file && -r $file ]]; then
        # Reading through EOF preserves trailing newlines. Bash strings cannot
        # represent NUL, so leave binary input unknown.
        if ! IFS= read -r -d '' bytes < "$file"; then
            contents=literal:$bytes
        fi
    fi
}

open_credential_stream() {
    local contents=''
    case $input_source in
        literal:*) contents=$input_source$'\n' ;;
        bytes:*) contents=literal:${input_source#bytes:} ;;
        file:*) credential_file_content "${input_source#file:}" ;;
    esac
    if [[ $contents == literal:* ]]; then
        input_source=stream:${#credential_streams[@]}
        credential_streams+=("${contents#literal:}")
    fi
}

# Only modeled input bytes reach the builtin; source code is never executed.
apply_credential_mapfile() {
    local input_source contents='' stored_array_fact='' input='' known=0 remainder=''
    local -a values=()
    (( ! dead_branches )) || return 0
    credential_input_source
    if [[ $input_source == @(closed|unbound) ]]; then builtin_status=1; return 0; fi
    bind_credential_input
    case $input_source in
        literal:*) input=${input_source#literal:}$'\n'; known=1 ;;
        bytes:*) input=${input_source#bytes:}; known=1 ;;
        stream:*) input=${credential_streams[${input_source#stream:}]}; known=1 ;;
        file:*)
            credential_file_content "${input_source#file:}"
            if [[ $contents == literal:* ]]; then input=${contents#literal:}; known=1; fi
            ;;
    esac
    if [[ $input == *$'\034unknown-input\034'* ||
          ( $input_source == stream:* && ${unknown_streams[${input_source#stream:}]+present} ) ]]; then known=0; fi
    if (( known && ! map_unknown )) && [[ -z $map_operand ]]; then
        {
            if builtin mapfile "${map_options[@]}" values; then
                store_credential_array_fact values
            fi
            IFS= read -r -d '' remainder || :
        } < <(printf '%s' "$input")
    fi
    change_credential_state "fact:$map_target" "${stored_array_fact:-array-unknown}"
    if [[ $input_source == stream:* && -n $stored_array_fact ]]; then
        credential_streams[${input_source#stream:}]=$remainder
    fi
}

evaluate_credential_read() {
    local input_source=$1 index fact stored_array_fact REPLY name subscript file
    local input='' known=0 eof=0 replaying_function=1 stream='' remainder=''
    local status=-1
    local -a values=() targets=() options=()
    (( ! dead_branches && ! read_poll )) || return 0
    if [[ $input_source == @(closed|unbound) ]]; then read_status=1; return 0; fi
    # Bash consumes input and assigns targets in order, stopping at the first
    # readonly target. Keep all names during field splitting (the last name
    # receives the remainder), then apply only the writable prefix.
    local readonly_index=-1 target_index=0
    local -a readonly_targets=("${read_names[@]}")
    if [[ -n $read_array ]]; then readonly_targets=("$read_array")
    elif (( ! ${#readonly_targets[@]} )); then readonly_targets=(REPLY)
    fi
    for name in "${readonly_targets[@]}"; do
        local index=''
        if [[ $name =~ $assignment_target$ ]]; then index=${BASH_REMATCH[2]} name=${BASH_REMATCH[1]}; fi
        if resolve_credential_nameref && [[ ${readonly_facts[$name]:-0} == 1 ]]; then
            readonly_index=$target_index
            break
        fi
        target_index=$((target_index + 1))
    done
    if (( ! read_other_fd )); then
        bind_credential_input
        case $input_source in
            literal:*) known=1; input=${input_source#literal:}$'\n' ;;
            bytes:*) known=1; input=${input_source#bytes:} ;;
            stream:*)
                stream=${input_source#stream:}
                known=1
                input=${credential_streams[stream]}
                ;;
            file:*)
                file=${input_source#file:}
                local contents=''
                credential_file_content "$file"
                if [[ $contents == literal:* ]]; then
                    known=1
                    input=${contents#literal:}
                fi
                ;;
        esac
    fi
    if [[ -n $stream && ${unknown_streams[$stream]+present} ]]; then known=0; fi
    if [[ $input == *$'\034unknown-input\034'* ]] &&
            { [[ -n $read_limit ]] || (( read_custom_delimiter )); }; then
        known=0
        # The unknown line's length/delimiters cannot establish a cursor for
        # later reads. Default line reads can still retain the known suffix.
        if [[ -n $stream ]]; then unknown_streams[$stream]=1; fi
    fi
    if [[ -z $input ]]; then eof=1; fi
    if (( read_raw )); then options+=(-r); fi
    if (( ! eof )); then
        if [[ $read_limit == unknown ]]; then known=0
        elif [[ -n $read_limit ]]; then options+=("-${read_limit%%:*}" "${read_limit#*:}"); fi
        if (( read_custom_delimiter )); then options+=(-d "$read_delimiter"); fi
    fi
    if (( known )); then
        # Feed only literal bytes modeled by the checker. Keep the unread
        # suffix for other commands using this same redirection.
        if [[ -z $read_array ]] && (( ! ${#read_names[@]} )); then
            targets=(REPLY)
        else
            for index in "${!read_names[@]}"; do targets+=("values[$index]"); done
        fi
        if [[ $separator == scalar:* || -z $input || -n $stream ]]; then
            local IFS=${separator#scalar:}
            {
                status=0
                if [[ $separator != scalar:* && -n $input && ( ${#read_names[@]} != 0 || -n $read_array ) ]]; then
                    IFS= read "${options[@]}" remainder || status=$?
                elif [[ -n $read_array ]]; then
                    read "${options[@]}" -a values || status=$?
                    store_credential_array_fact values
                else
                    read "${options[@]}" "${targets[@]}" || status=$?
                fi
                if [[ -n $stream ]]; then
                    IFS= read -r -d '' remainder || :
                    credential_streams[stream]=$remainder
                fi
            } < <(printf '%s' "$input")
        fi
    fi
    if [[ ${input:0:${#input}-${#remainder}} == *$'\034unknown-input\034'* ]]; then
        values=()
        unset REPLY
        stored_array_fact=''
        status=-1
    fi
    read_status=$status
    if (( readonly_index >= 0 )); then read_status=1 builtin_status=1; fi
    if (( readonly_index == 0 )); then return 0; fi
    if [[ -n $read_array ]]; then
        change_credential_state "fact:$read_array" "${stored_array_fact:-array-unknown}"
    elif (( ! ${#read_names[@]} )); then
        fact=set
        if [[ ${REPLY+present} ]]; then fact=scalar:$REPLY; fi
        change_credential_state fact:REPLY "$fact"
    else
        for index in "${!read_names[@]}"; do
            if (( readonly_index >= 0 && index >= readonly_index )); then break; fi
            fact=set
            if [[ ${values[index]+present} ]]; then fact=scalar:${values[index]}; fi
            [[ ${read_names[index]} =~ $assignment_target ]]
            name=${BASH_REMATCH[1]}
            subscript=${BASH_REMATCH[2]}
            if [[ -n $subscript ]]; then
                assign_credential_element "$name" "$subscript" "$fact"
            else
                change_credential_state "fact:$name" "$fact"
            fi
        done
    fi
}

# A return can leave a function through a compound command that executes
# in its shell, including a reached iteration of a known loop.
credential_return_scope() {
    local index kind scope
    for (( index=-1; -index<=${#credential_scopes[@]}; index-- )); do
        scope=${isolated_scopes[index]}
        if (( scope == function_depth )); then return 0; fi
        kind=${credential_scopes[index]}
        case $kind in
            if|case|brace|loop)
                (( ! ${compound_scopes[$scope]:-0} )) || return 1
                if (( diagnostic_function == function_depth || replaying_loop_function == function_depth )); then
                    local site=${scope_sites[$scope]}
                    [[ ! ${detached_compound_sites[$site]+present} ]] || return 1
                    case $kind in
                        loop) [[ ${certain_loop_bodies[$scope]+present} ]] || return 1 ;;
                        if) [[ ${certain_if_arms[$scope]:-0} == 1 ]] || return 1 ;;
                        case)
                            local arm=${return_case_indices[$scope]}
                            (( case_known[arm] && case_certain[arm] && ! case_prior_possible[arm] )) || return 1
                            ;;
                    esac
                fi
                ;;
            *) return 1 ;;
        esac
    done
    return 1
}

# Keep empty and unknown contents distinct. Only literal arguments reach
# the formatting builtins, and printf directives are bounded.
credential_output_contents() {
    local format rendered
    if (( command_redirection_failed )); then contents=literal:; return; fi
    if (( ${command_output_ready:-0} )); then contents=$command_output_cached; return; fi
    if [[ -n ${compound_stdout:-} ]]; then contents=$compound_stdout; return; fi
    if (( for_header || select_header || ${#arithmetic_commands[@]} )); then contents=literal:; return; fi
    case $output_command in
        :|true|false|read|getopts|test) contents=literal: ;;
        cd)
            # cd - and CDPATH can print a directory; only silent, resolved
            # directory changes (or known failures) establish empty output.
            if (( cd_status == 1 )) || [[ -n $cd_directory ]]; then contents=literal:; fi
            ;;
        echo)
            # EOF preserves trailing newlines. A NUL ends the read early
            # and leaves the file unknown because Bash facts cannot store it.
            if ! IFS= read -r -d '' rendered < <(shopt -u xpg_echo; builtin echo "${output_words[@]}"); then
                contents=literal:$rendered
            fi
            ;;
        printf)
            local index=0 numeric_directive=0
            if [[ ${output_words[0]:-} == -- ]]; then index=1; fi
            [[ ${output_words[index]+present} ]] || return 0
            format=${output_words[index]}
            local rest=$format directive='^%([-+ #0]*[0-9]{0,3}([.][0-9]{0,3})?[diouxXfFeEgGaAcbsq]|%)'
            { (( index )) || [[ $format != -?* ]]; } || return 0
            while [[ $rest == *%* ]]; do
                rest=%${rest#*%}
                [[ $rest =~ $directive ]] || return 0
                if [[ ${BASH_REMATCH[0]: -1} == [diouxXfFeEgGaA] ]]; then numeric_directive=1; fi
                rest=${rest:${#BASH_REMATCH[0]}}
            done
            if ! IFS= read -r -d '' rendered < <(
                # Numeric conversion errors still write deterministic bytes.
                builtin printf -- "$format" "${output_words[@]:index+1}" 2>/dev/null ||
                    (( numeric_directive )) || builtin printf '\0'
            ); then
                contents=literal:$rendered
            fi
            ;;
        cat)
            local file part combined=''
            if (( ! ${#output_words[@]} )); then
                local input_source
                credential_input_source
                bind_credential_input
                open_credential_stream
                if [[ $input_source == stream:* && ! ${unknown_streams[${input_source#stream:}]+present} ]]; then
                    contents=literal:${credential_streams[${input_source#stream:}]}
                    command_consumed_stream=${input_source#stream:}
                fi
                return 0
            fi
            for part in "${output_words[@]}"; do
                [[ $part != -* ]] || return 0
                file=$(credential_file_path "$part") || return 0
                contents=''
                credential_file_content "$file"
                [[ $contents == literal:* ]] || { contents=''; return 0; }
                combined+=${contents#literal:}
            done
            contents=literal:$combined
            ;;
        '')
            if (( command_position )) && [[ -z $command_prefix ]]; then contents=literal:; fi
            ;;
    esac
}

# Walk known argv without evaluating source code. OPTIND is one-based; the
# separate character offset retains progress through clustered options.
apply_credential_getopts() {
    local applying_getopts=1 optstring=${getopts_words[0]:-} name=${getopts_words[1]:-}
    local ind=${variable_facts[OPTIND]:-unknown} offset=$getopts_offset word option tail
    local silent=0 result='?' argument=unset status=0 first count
    local -a argv=()
    (( ! dead_branches )) || return 0
    [[ $name =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || return 0
    if (( ${#getopts_words[@]} > 2 )); then
        argv=("${getopts_words[@]:2}")
    elif [[ -n $positional_bounds ]]; then
        first=${positional_bounds%:*}
        count=$((${positional_bounds#*:} - first))
        argv=("${known_array_words[@]:first:count}")
    elif (( ! positional_empty )); then getopts_known=0
    fi
    if (( ! getopts_known )) || [[ ! $ind =~ ^scalar:([0-9]{1,8})$ ]]; then
        change_credential_state "fact:$name" unknown
        change_credential_state fact:OPTARG unknown
        change_credential_state fact:OPTIND unknown
        change_credential_state getopts_offset 1
        return 0
    fi
    ind=$((10#${BASH_REMATCH[1]}))
    if (( ind < 1 )); then ind=1; offset=1; fi
    if [[ $optstring == :* ]]; then silent=1; optstring=${optstring:1}; fi
    word=${argv[ind-1]:-}
    if (( ind > ${#argv[@]} )) || [[ $word != -?* || $word == -- ]]; then
        status=1
        if [[ $word == -- ]]; then ind=$((ind + 1)); fi
        offset=1
    else
        option=${word:offset:1}
        offset=$((offset + 1))
        if (( offset >= ${#word} )); then ind=$((ind + 1)); offset=1; fi
        if [[ -n $option && $option != ':' && $optstring == *"$option"* ]]; then
            result=$option
            tail=${optstring#*"$option"}
            if [[ $tail == :* ]]; then
                if (( offset > 1 )); then
                    argument=scalar:${word:offset}
                    ind=$((ind + 1))
                elif (( ind <= ${#argv[@]} )); then
                    argument=scalar:${argv[ind-1]}
                    ind=$((ind + 1))
                else
                    result='?'
                    if (( silent )); then result=:; argument=scalar:$option; fi
                fi
                offset=1
            fi
        elif (( silent )); then argument=scalar:$option
        fi
    fi
    change_credential_state "fact:$name" "scalar:$result"
    change_credential_state fact:OPTARG "$argument"
    change_credential_state fact:OPTIND "scalar:$ind"
    change_credential_state getopts_offset "$offset"
    builtin_status=$status
}

# Drop known positional words only when shift can succeed.
apply_credential_shift() {
    local count=$shift_count first last
    (( ! dead_branches )) || return 0
    if (( ! shift_known )); then
        change_credential_state positional_empty 0
        change_credential_state positional_bounds ''
        return 0
    fi
    builtin_status=1
    (( shift_operands <= 1 )) || return 0
    # shift accepts a decimal integer, not an arithmetic expression. Invalid
    # and out-of-range counts fail without modifying the positional parameters.
    [[ $count =~ ^[[:space:]]*([+]?[0-9]+)[[:space:]]*$ ]] || return 0
    count=${BASH_REMATCH[1]}
    count=${count#+}
    while [[ ${#count} -gt 1 && $count == 0* ]]; do count=${count:1}; done
    (( ${#count} < 19 )) || return 0
    count=$((10#$count))
    if (( ! count )); then builtin_status=0; return 0; fi
    if [[ -n $positional_bounds ]]; then
        first=${positional_bounds%:*}
        last=${positional_bounds#*:}
        (( count <= last - first )) || return 0
        builtin_status=0
        first=$((first + count))
        change_credential_state positional_bounds "$first:$last"
        change_credential_state positional_empty "$((first == last))"
    elif (( ! positional_empty )); then
        builtin_status=2
        change_credential_state positional_bounds ''
        change_credential_state positional_empty 0
    fi
}

finish_credential_readonly_condition() {
    local connector=${source:position:2}
    # A readonly error aborts the entire AND-OR list, regardless of negation
    # or connector. Keep compound RHS commands dead through their closing token.
    if (( command_position && ! ${#pending_names[@]} )) &&
            [[ -z $output_command && -z $pending_function ]]; then return 0; fi
    if (( readonly_chain_skip && ${#credential_scopes[@]} != readonly_chain_depth )); then return 0; fi
    if (( readonly_chain_skip )); then
        if [[ $connector == @('&&'|'||') ]]; then return 0; fi
        dead_branches=$((dead_branches - 1))
        readonly_chain_skip=0
    elif (( readonly_command_failed )) && [[ $connector == @('&&'|'||') ]]; then
        readonly_chain_skip=1
        readonly_chain_depth=${#credential_scopes[@]}
        dead_branches=$((dead_branches + 1))
    fi
    command_negated=0
}

# Known builtin statuses share the same condition and short-circuit path.
# Unknown commands never prove a branch dead. A skipped compound RHS keeps
# its mark until the command boundary in the scope that started the list.
finish_credential_builtin_status() {
    local connector=${source:position:2} depth=${#credential_scopes[@]} combined_status
    (( ! command_position )) || return 0
    # Opening/duplicating a descriptor may fail before the builtin runs.
    # A known failure remains failure; success is no longer guaranteed.
    if (( builtin_status == 0 && command_redirection_unknown )); then builtin_status=2; fi
    if (( builtin_skip )); then
        (( depth == builtin_skip_depth )) || return 0
        (( ! command_position )) || return 0
        dead_branches=$((dead_branches - 1))
        builtin_skip=0
        builtin_status=$builtin_skip_status
    elif (( dead_branches || detached_command || pipeline_next )); then
        return 0
    elif (( command_negated && builtin_status < 2 )); then
        builtin_status=$((1 - builtin_status))
    fi
    if [[ ${builtin_chain_operator[$depth]+present} ]]; then
        combine_credential_test_status "${builtin_chain_status[$depth]}" "${builtin_chain_operator[$depth]}" "$builtin_status"
        builtin_status=$combined_status
        if (( builtin_status == 2 && ${#if_conditions[@]} )) && [[ ${if_conditions[-1]} == waiting-* ]]; then
            if_conditions[-1]=waiting-unknown
        fi
    fi
    if [[ $connector == @('&&'|'||') ]]; then
        builtin_chain_operator[$depth]=$connector builtin_chain_status[$depth]=$builtin_status
    else
        unset 'builtin_chain_operator[$depth]' 'builtin_chain_status[$depth]'
    fi
    if (( ${#if_conditions[@]} )) && [[ ${if_conditions[-1]} == waiting-* ]]; then
        case $builtin_status in
            0) if_conditions[-1]=waiting-true ;;
            1) if_conditions[-1]=waiting-false ;;
        esac
    fi
    if [[ $builtin_status:$connector == @('0:||'|'1:&&') ]]; then
        builtin_skip=1 builtin_skip_depth=$depth builtin_skip_status=$builtin_status
        dead_branches=$((dead_branches + 1))
    fi
}

credential_stdout_redirected() {
    local scope
    [[ ${command_fds[1]+present} || ${persistent_descriptors[1]+present} ]] && return 0
    for scope in "${isolated_scopes[@]}"; do
        [[ ${scope_descriptors[$scope:1]+present} ]] && return 0
    done
    return 1
}

finish_credential_command() {
    local index offset first bounds output disposition input_source
    local readonly_command_failed=0 builtin_status=$cd_status command_consumed_stream=''
    if (( command_redirection_failed )); then dead_branches=$((dead_branches + 1)); fi
    local contents='' command_output_cached='' command_output_ready=0
    credential_output_contents
    command_output_cached=$contents command_output_ready=1
    if [[ $command_prefix == exec ]] && (( command_position && ! dead_branches )); then
        local descriptor
        for descriptor in "${!command_fds[@]}"; do
            credential_descriptor_source "$descriptor"
            local scope_index input_scope descriptor_key=fd:$descriptor
            for (( scope_index=${#isolated_scopes[@]}-1; scope_index>=0; scope_index-- )); do
                input_scope=${isolated_scopes[scope_index]}
                if [[ ${scope_descriptors[$input_scope:$descriptor]+present} ||
                      ( $descriptor == 0 && ${scope_inputs[$input_scope]+present} ) ]]; then
                    descriptor_key=scopefd:$input_scope:$descriptor
                    break
                fi
            done
            change_credential_state "$descriptor_key" "$input_source"
        done
    fi
    if [[ ${source:position:1} == '|' && ${source:position:2} != '||' ]]; then
        local contents=''
        credential_output_contents
        pipeline_input=unknown
        if [[ $contents == literal:* && ${#command_outputs[@]} == 0 ]] && ! credential_stdout_redirected; then
            input_source=bytes:${contents#literal:}
            open_credential_stream
            pipeline_input=$input_source
        fi
    fi
    if [[ ${scope_stdout[${isolated_scopes[-1]}]+present} ]] && (( ! dead_branches )); then
        local contents='' output_scope=${isolated_scopes[-1]}
        if [[ ${source:position:1} != '|' || ${source:position:2} == '||' ]]; then
            credential_output_contents
            if (( conditional_chain || detached_command || ${#command_outputs[@]} )) ||
                    credential_stdout_redirected || [[ $contents != literal:* ]]; then
                scope_stdout[$output_scope]=unknown
            elif [[ ${scope_stdout[$output_scope]} == literal:* ]]; then
                scope_stdout[$output_scope]+=${contents#literal:}
            fi
        fi
    fi
    if (( ! env_rejected )); then
        for offset in "${env_assignments[@]}"; do
            report_credential_assignment "$offset"
            reported=1
        done
    fi
    env_assignments=()
    env_null=0
    env_rejected=0
    env_operands_started=0
    if (( ! dead_branches )) &&
            { (( ! detached_command )) || [[ ${source:position:1} == '|' ]]; }; then
        local contents=''
        credential_descriptor_source 1
        if [[ $input_source == output:* && -z ${compound_stdout:-} ]]; then
            output=${input_source#output:}
            credential_output_contents
            disposition=''
            if [[ $contents == literal:* ]]; then disposition=append:$contents; fi
            apply_credential_output "$output" "$disposition"
        fi
    fi
    if (( ! dead_branches )) && [[ -n $command_consumed_stream ]]; then
        credential_streams[command_consumed_stream]=''
    fi
    if (( ! dead_branches && ! suppress_command_writes )); then
        case $output_command in
            rm) apply_credential_remove ;;
            mkdir) apply_credential_mkdir ;;
            touch|cp|mv|install|ln|unlink|rmdir|truncate|chmod|chown|chgrp)
                # These commands may change existence, type or permissions.
                # Without a full operation model, old metadata proves nothing.
                change_credential_state file:uncertain 1 ;;
        esac
    fi
    # Constant builtins use the same pipeline negation and AND-OR handling
    # as reads and shifts, including when a loop condition is replayed.
    case $output_command in
        true|:) builtin_status=0 ;;
        false) builtin_status=1 ;;
    esac
    if (( printf_terminated )); then builtin_status=0; fi
    if (( declaration_invalid || builtin_failed )); then builtin_status=1; fi
    if (( printf_readonly )); then builtin_status=1; fi
    # Prefix assignments on a command do not set facts in the outer shell.
    if (( ! detached_command )); then
        if (( ! pipeline_next || (lastpipe && ! monitor_mode) )); then
            if (( read_builtin )); then apply_credential_read; fi
            if (( map_builtin )); then apply_credential_mapfile; fi
            if (( ! dead_branches )) &&
                    [[ ${source:position:1} != '|' || ${source:position:2} == '||' ]]; then
                local expression arithmetic_result arithmetic_status
                if (( let_arguments )); then builtin_status=1; fi
                for expression in "${arithmetic_commands[@]}"; do
                    arithmetic_status=0
                    arithmetic_result=$(credential_arithmetic_value "$expression") || arithmetic_status=$?
                    apply_credential_arithmetic_assignment "$expression"
                    if (( arithmetic_assignment_failed )); then
                        builtin_status=1
                        if (( arithmetic_assignment_fatal )); then abort_credential_condition || :; fi
                        break
                    fi
                    if [[ -n $arithmetic_assignment_result ]]; then
                        arithmetic_result=$arithmetic_assignment_result arithmetic_status=0
                    fi
                    case $arithmetic_status in
                        0) builtin_status=$((arithmetic_result == 0)) ;;
                        2) builtin_status=1; break ;;
                        *) builtin_status=2 ;;
                    esac
                done
            fi
            if (( getopts_builtin )); then apply_credential_getopts; fi
            if (( shift_builtin )); then
                apply_credential_shift
            fi
            if (( set_positionals )); then
                change_credential_state positional_empty "$((set_positionals == 1))"
                bounds=''
                if (( positional_words_known )); then
                    first=${#known_array_words[@]}
                    known_array_words+=("${positional_words[@]}")
                    bounds=$first:${#known_array_words[@]}
                fi
                change_credential_state positional_bounds "$bounds"
            fi
            if [[ -n $lastpipe_setting ]]; then change_credential_state lastpipe "$lastpipe_setting"; fi
            if [[ -n $monitor_setting ]]; then change_credential_state monitor_mode "$monitor_setting"; fi
            if [[ -n $dotglob_setting ]]; then change_credential_state dotglob "$dotglob_setting"; fi
            if (( unset_home )); then change_credential_state fact:HOME ''; fi
            case $oldpwd_effect in
                set) apply_credential_cd "$cd_destination" "$cd_directory" "$cd_mode" ;;
                unset) change_credential_state fact:OLDPWD '' ;;
            esac
            if (( command_position )) && [[ -z $command_prefix ]]; then
                for index in "${!pending_names[@]}"; do
                    resolve_credential_pending_alias "$index"
                    if [[ -n ${pending_indices[index]} ]]; then
                        assign_credential_element "${pending_names[index]}" "${pending_indices[index]}" "${pending_facts[index]}" 0 1
                    else
                        change_credential_state "fact:${pending_names[index]}" "${pending_facts[index]}" 0 "${pending_values[index]}" 1
                    fi
                done
            fi
        fi
    fi
    if [[ -n $pending_function ]]; then
        credential_input_source
        bounds=''
        if (( call_words_known )); then
            first=${#known_array_words[@]}
            known_array_words+=("${call_words[@]}")
            bounds=$first:${#known_array_words[@]}
        fi
        apply_credential_function "$pending_function" "$pipeline_next" "$input_source" "$detached_command" "$bounds"
    fi
    if (( pending_return && ! detached_command && ! conditional_chain )) &&
            (( ! pipeline_next || (lastpipe && ! monitor_mode) )) &&
            [[ ${source:position:2} != @('&&'|'||') ]] && credential_return_scope; then
        local return_site=$logical_start:$((pending_return - 1))$for_iteration_path
        if (( diagnostic_function == function_depth )); then
            if (( ! dead_branches )); then printf 'return %s\n' "$return_site"; fi
        elif (( replaying_loop_function == function_depth )); then
            if (( ! dead_branches )); then reached_loop_return=1; fi
        elif (( ! replaying_function )); then
            if (( isolated_scopes[-1] == function_depth )); then
                if (( ! dead_branches )); then
                    record_credential_function_effect "$function_depth" return '' '' 0
                fi
            else
                record_credential_function_effect "${isolated_scopes[-1]}" conditional-return "$return_site" '' 0
                conditional_function_returns[$function_depth]=1
            fi
        fi
        if (( ! dead_branches && (diagnostic_function == function_depth ||
                isolated_scopes[-1] == function_depth || replaying_loop_function == function_depth) )); then
            returned_functions[$function_depth]=1
            dead_branches=$((dead_branches + 1))
        fi
    fi
    if (( command_redirection_failed )); then
        dead_branches=$((dead_branches - 1))
        builtin_status=1
        command_position=0
    fi
    if (( command_position && ${#pending_names[@]} )) && [[ -z $command_prefix ]]; then
        builtin_status=$assignment_substitution_status
        command_position=0
    fi
    finish_credential_builtin_status
    if [[ ${substitution_last_status+x} ]] && (( ! dead_branches && ! command_position )); then
        substitution_last_status=$builtin_status
    fi
    if (( (read_builtin || shift_builtin || builtin_status < 2) && ${#if_conditions[@]} )) &&
            [[ ${condition_kind[-1]} == @(while|until) && ${if_conditions[-1]} == waiting-* ]]; then
        local condition_scope=${isolated_scopes[-1]}
        # Presence enables replay; the value records whether exhaustion of
        # the iteration bound must invalidate positional facts (a shift ran).
        loop_condition_commands[$condition_scope]=$((shift_builtin || ${loop_condition_commands[$condition_scope]:-0}))
    fi
    finish_credential_readonly_condition
    arithmetic_expansion_failed=0
    declaration_invalid=0 declaration_no_writes=0 printf_readonly=0 printf_terminated=0 command_redirection_unknown=0 command_redirection_failed=0 suppress_command_writes=0
    builtin_failed=0 mkdir_arguments=0 mkdir_options=1
    pending_return=0
    if [[ -n $pending_loop_action ]] && (( ! detached_command )) &&
            (( ! pipeline_next || (lastpipe && ! monitor_mode) )); then
        control_credential_loop "$pending_loop_action" "$pending_loop_level" "$logical_start:$position$for_iteration_path"
    fi
    pending_loop_action=''
    pending_loop_level=''
    if (( local_options && ! dead_branches && ! detached_command )) &&
            (( ! pipeline_next || (lastpipe && ! monitor_mode) )); then
        if (( replaying_loop_function && replaying_loop_function == function_depth )); then
            save_credential_caller_options
        elif (( ! replaying_function )) && records_credential_effects "${isolated_scopes[-1]}"; then
            record_credential_function_effect "${isolated_scopes[-1]}" local-options '' '' 0
        fi
    fi
    local_options=0
    local_builtin=0
    assignment_substitution_status=0
    pending_names=()
    pending_facts=()
    pending_indices=()
    pending_values=()
    pending_function=''
    arithmetic_commands=()
    call_words=() call_words_known=1
    set_positionals=0
    getopts_builtin=0 getopts_known=1 getopts_words=()
    shift_builtin=0 shift_count=1 shift_known=1 shift_operands=0 shift_options=1
    positional_words=()
    positional_words_known=1
    lastpipe_setting=''
    monitor_setting=''
    dotglob_setting=''
    oldpwd_effect=''
    cd_destination=''
    cd_directory=''
    cd_mode=-L
    cd_status=2
    unset_home=0
    read_builtin=0 read_options=1 read_operand='' read_array='' read_raw=0
    map_builtin=0 map_unknown=0 map_operand='' map_target=MAPFILE map_options=()
    read_limit='' read_poll=0 read_other_fd=0 read_delimiter='' read_custom_delimiter=0
    command_noclobber=$noclobber
    command_input='' command_fds=() command_outputs=() output_command='' output_words=() read_descriptor=0 compound_stdout=''
    declaration_attributes=''
    associative_declaration=0 nameref_declaration=0 readonly_declaration=0 integer_declaration=0 function_declaration=0
    read_names=()
}

credential_has_readonly_abort_scope() {
    local kind scope index
    for (( index=-1; -index<=${#credential_scopes[@]}; index-- )); do
        kind=${credential_scopes[index]}
        scope=${isolated_scopes[index]}
        if [[ $kind == paren || $kind == function-* ]]; then return 0; fi
        if (( ${compound_scopes[$scope]:-0} )) ||
                [[ -n ${scope_sites[$scope]:-} && ${detached_compound_sites[${scope_sites[$scope]}]+present} ]]; then
            return 0
        fi
    done
    (( ${credential_subshell_context:-0} ))
}

# Journal writes in the innermost isolated scope. The first write saves the
# entry value. Function summaries replay writes in source order, with
# local facts visible until the function returns.
change_credential_state() {
    local key=$1 value=$2 body_local=${3:-0} scope=${isolated_scopes[-1]}
    local entry=$scope:$key name=${1#*:} previous fatal_write=${5:-0}
    (( ! ${suppress_command_writes:-0} && ! ${arithmetic_expansion_failed:-0} )) || return 0
    (( ! dead_branches )) || return 0
    if [[ $key == function:* && ${readonly_facts[function:$name]:-0} == 1 ]]; then return 0; fi
    if [[ $key == fact:* && ${readonly_facts[$name]:-0} == 1 ]]; then
        # Keep rejected writes in summaries so replay unwinds the same
        # compound chain when the assignment is reached at the call site.
        if (( ! replaying_function )) && records_credential_effects "$scope"; then
            record_credential_function_effect "$scope" "$key" "$value" "${4:-}" "$(credential_fact_is_local "$key")" "$fatal_write"
        fi
        # Failed unsets do not have the fatal semantics of assignments.
        if (( fatal_write )); then
            readonly_command_failed=1
            if (( ${#if_conditions[@]} )) && [[ ${if_conditions[-1]} == waiting-* ]]; then
                abort_credential_readonly
            elif (( ! ${#credential_scopes[@]} )) &&
                    { (( ${conditional_chain:-0} )) || [[ ${source:position:2} == @('&&'|'||') ]]; }; then
                : # finish_credential_readonly_condition skips the rest of this list.
            elif (( ${replaying_readonly_call:-0} )); then
                readonly_call_aborted=1
            else
                abort_credential_readonly
            fi
        fi
        return 0
    fi
    if (( body_local && replaying_loop_function && replaying_loop_function == function_depth )) &&
            [[ $key == attributes:* && ! ${caller_attributes[$name]+present} ]]; then
        caller_attributes[$name]=${attribute_facts[$name]:-}
    fi
    if (( body_local && replaying_loop_function && replaying_loop_function == function_depth )) &&
            [[ $key == integer:* && ! ${caller_integer[$name]+present} ]]; then
        caller_integer[$name]=${integer_facts[$name]:-}
    fi
    if (( body_local && replaying_loop_function && replaying_loop_function == function_depth )) &&
            [[ $key == readonly:* && ! ${caller_readonly[$name]+present} ]]; then
        caller_readonly[$name]=${readonly_facts[$name]:-}
    fi
    if (( body_local && replaying_loop_function && replaying_loop_function == function_depth )) &&
            [[ $key == fact:* && ! ${caller_facts[$name]+present} ]]; then
        caller_facts[$name]=${variable_facts[$name]:-}
    fi
    if (( scope )) && [[ $scope != "${positional_call_scope:-}" || $key != positional_* ]]; then
        if [[ ! ${saved_state[$entry]+present} ]]; then
            case $key in
                scopefd:*) previous=${scope_descriptors[$name]:-} ;;
                fd:*) previous=${persistent_descriptors[${key#fd:}]:-} ;;
                fact:*) previous=${variable_facts[$name]:-} ;;
                readonly:*) previous=${readonly_facts[$name]:-} ;;
                attributes:*) previous=${attribute_facts[$name]:-} ;;
                integer:*) previous=${integer_facts[$name]:-} ;;
                file:*) previous=${credential_file_contents[$name]:-} ;;
                function:*) previous=${credential_functions[$name]:-} ;;
                *) previous=${!key} ;;
            esac
            saved_state[$entry]=$previous
            scope_writes[$scope]+=" $key"
        fi
        if (( body_local )); then local_facts[$entry]=1; fi
        if (( ! replaying_function )) && records_credential_effects "$scope"; then
            record_credential_function_effect "$scope" "$key" "$value" "${4:-}" "$(credential_fact_is_local "$key")" "$fatal_write"
        fi
    fi
    if [[ $key == fact:OPTIND ]] && (( ! ${applying_getopts:-0} )); then
        change_credential_state getopts_offset 1
    fi
    if [[ $key == fact:* ]]; then normalize_credential_assignment_fact "$name" ''; fi
    restore_credential_state "$key" "$value"
}

# Store declaration flags separately from values, preserving lexical scopes.
apply_credential_attributes() {
    local name=$1 body_local=$2 index='' attrs option letter j case_flags='' 
    if (( ! nameref_declaration && ! body_local )); then resolve_credential_nameref || return 0; fi
    attrs=${attribute_facts[$name]:-}
    if (( body_local )) && [[ $(credential_fact_is_local "fact:$name") == 0 ]]; then attrs=''; fi
    for option in $declaration_attributes; do
        for ((j=1; j<${#option}; j++)); do
            letter=${option:j:1}
            [[ $letter == [aAilunrxt] ]] || continue
            if [[ $letter == [lu] && ${option:0:1} == - ]]; then case_flags+=$letter; fi
            attrs=${attrs//$letter/}
            if [[ ${option:0:1} == - ]]; then
                if [[ $letter == l ]]; then attrs=${attrs//u/}; fi
                if [[ $letter == u ]]; then attrs=${attrs//l/}; fi
                attrs+=$letter
            fi
        done
    done
    if [[ $case_flags == *l* && $case_flags == *u* ]]; then attrs=${attrs//l/}; attrs=${attrs//u/}; fi
    change_credential_state "attributes:$name" "$attrs" "$body_local"
}

# Normalize writes at execution time, after journaling the original word.
# In particular, function summaries must not freeze the old value of +=.
normalize_credential_assignment_fact() {
    local name=$1 subscript=$2 current numeric rhs bounds offset partial=0 stored_array_fact
    if [[ $value == append:* ]]; then
        rhs=${value#append:}
        credential_parameter_fact "$name" "$subscript"
        if [[ $current == unset ]]; then current=scalar:; fi
        if [[ $current != scalar:* ]]; then value=unknown; return; fi
        if [[ ${integer_facts[$name]:-0} == 1 ]]; then
            current=${current#scalar:}
            value="scalar:(${current:-0}) + (${rhs:-0})"
        else
            value=scalar:${current#scalar:}$rhs
        fi
    fi
    local case_attribute=${attribute_facts[$name]:-}
    if [[ ${integer_facts[$name]:-0} != 1 && ( $case_attribute == *l* || $case_attribute == *u* ) ]]; then
        if [[ $value == scalar:* ]]; then
            rhs=${value#scalar:}
            if [[ $case_attribute == *l* ]]; then value=scalar:${rhs,,}
            else value=scalar:${rhs^^}; fi
        elif [[ $value == @(array:*|partial-array:*|assoc-array:*) ]]; then
            # Copy the immutable element range before changing per-value data.
            bounds=${value#*:}
            local first=${#known_array_words[@]} original_kind=${value%%:*}
            for ((offset=${bounds%:*}; offset<${bounds#*:}; offset++)); do
                rhs=${known_array_words[offset]}
                if [[ $case_attribute == *l* ]]; then rhs=${rhs,,}; else rhs=${rhs^^}; fi
                local next=${#known_array_words[@]}
                known_array_words+=("$rhs")
                known_array_indices[next]=${known_array_indices[offset]}
                if [[ ${unknown_array_elements[offset]+present} ]]; then
                    unknown_array_elements[next]=${unknown_array_elements[offset]}
                fi
            done
            value=$original_kind:$first:${#known_array_words[@]}
        fi
    fi
    [[ ${integer_facts[$name]:-0} == 1 ]] || return 0
    if [[ $value == scalar:* ]]; then
        local arithmetic_assignment_result='' arithmetic_assignment_failed=0 arithmetic_assignment_fatal=0
        apply_credential_arithmetic_assignment "${value#scalar:}"
        if [[ -n $arithmetic_assignment_result ]]; then value=scalar:$arithmetic_assignment_result
        else value=unknown
        fi
    elif [[ $value == array:* || $value == partial-array:* ]]; then
        local -a elements=() unknown_elements=()
        if [[ $value == partial-array:* ]]; then partial=1; fi
        bounds=${value#*:}
        for (( offset=${bounds%:*}; offset<${bounds#*:}; offset++ )); do
            local element_index=${known_array_indices[offset]}
            local arithmetic_assignment_result='' arithmetic_assignment_failed=0 arithmetic_assignment_fatal=0
            if [[ -z ${unknown_array_elements[offset]:-} ]]; then
                apply_credential_arithmetic_assignment "${known_array_words[offset]}"
            fi
            if [[ -n $arithmetic_assignment_result ]]; then
                elements[element_index]=$arithmetic_assignment_result
            else
                elements[element_index]=''
                unknown_elements[element_index]=${unknown_array_elements[offset]:-unknown}
                partial=1
            fi
        done
        store_credential_array_fact elements
        offset=${stored_array_fact#array:}
        offset=${offset%:*}
        for element_index in "${!elements[@]}"; do
            if [[ -n ${unknown_elements[element_index]:-} ]]; then
                unknown_array_elements[offset]=${unknown_elements[element_index]}
            fi
            offset=$((offset + 1))
        done
        value=$stored_array_fact
        if (( partial )); then value=partial-$value; fi
    fi
}

# Local declarations in a compound command share the enclosing function's locals.
credential_fact_is_local() {
    local key=$1 scope
    for scope in "${isolated_scopes[@]}"; do
        if [[ ${local_facts[$scope:$key]:-0} == 1 ]]; then
            printf 1
            return
        fi
    done
    printf 0
}

records_credential_effects() {
    [[ ${function_scopes[$1]+present} ||
       ( $function_depth != 0 && ${compound_scopes[$1]+present} ) ]]
}

record_credential_function_effect() {
    local scope=$1 effect=$((++next_function_effect))
    function_writes[$scope]+=" $effect"
    function_keys[effect]=$2
    function_effects[effect]=$3
    function_values[effect]=$4
    function_locals[effect]=$5
    function_fatal_writes[effect]=${6:-0}
}

# readonly follows an existing nameref unless -n declares the alias itself.
mark_credential_readonly() {
    local name=$1 index='' body_local=$2
    if (( ! $3 && ! body_local )); then resolve_credential_nameref || return 0; fi
    if [[ ! ${variable_facts[$name]+present} ]]; then
        change_credential_state "fact:$name" unset "$body_local"
    fi
    if [[ $(credential_fact_is_local "fact:$name") == 1 ]]; then body_local=1; fi
    change_credential_state "readonly:$name" 1 "$body_local"
}

restore_credential_state() {
    local key=$1 value=$2 name=${1#*:}
    case $key in
        scopefd:*)
            if [[ -n $value ]]; then scope_descriptors[$name]=$value
            else unset 'scope_descriptors[$name]'; fi
            ;;
        fd:*)
            if [[ -n $value ]]; then persistent_descriptors[$name]=$value
            else unset 'persistent_descriptors[$name]'; fi
            ;;
        attributes:*)
            if [[ -n $value ]]; then attribute_facts[$name]=$value
            else unset 'attribute_facts[$name]'; fi
            ;;
        integer:*)
            if [[ -n $value ]]; then integer_facts[$name]=$value
            else unset 'integer_facts[$name]'
            fi
            ;;
        readonly:*)
            if [[ -n $value ]]; then readonly_facts[$name]=$value
            else unset 'readonly_facts[$name]'
            fi
            ;;
        fact:*)
            if [[ -n $value ]]; then
                variable_facts[$name]=$value
            else
                unset 'variable_facts[$name]'
            fi
            ;;
        file:*)
            if [[ -n $value ]]; then
                credential_file_contents[$name]=$value
            else
                unset 'credential_file_contents[$name]'
            fi
            ;;
        function:*)
            if [[ -n $value ]]; then
                credential_functions[$name]=$value
            else
                unset 'credential_functions[$name]'
            fi
            ;;
        *) printf -v "$key" '%s' "$value" ;;
    esac
}

enter_credential_scope() {
    local kind=$1 scope=$((++next_credential_scope)) input_source
    local redirection_failed=0 entered_redirections=0
    local -A redirected_fds=()
    if [[ -n ${2:-} ]]; then
        scope_sites[$scope]=$logical_start:$2
        local redirects=${compound_redirections[${scope_sites[$scope]}]:-}
        if [[ $kind == function-* ]]; then
            function_definition_redirections[$scope]=$redirects
            function_definition_redirection_origins[$scope]=${compound_redirection_origins[${scope_sites[$scope]}]:-}
        elif [[ -n $redirects ]]; then
            if (( function_depth && ! replaying_function )); then
                function_replay_bodies[$function_depth]=1
            fi
            if (( ! function_depth || replaying_function || diagnostic_function )); then
                inspect_credential_redirections "$redirects" "${compound_redirection_origins[${scope_sites[$scope]}]:-}"
                entered_redirections=1
                opened_compound_redirections[$scope]=1
                if (( redirection_failed )); then
                    failed_compound_scopes[$scope]=1
                    dead_branches=$((dead_branches + 1))
                fi
            fi
        fi
        if [[ $kind == function-* ]]; then
            scope_inputs[$scope]=function-input
            scope_replay_inputs[$scope]=function-input
        fi
        input_source=${compound_inputs[${scope_sites[$scope]}]:-}
        if [[ -n $input_source ]]; then
            if [[ $kind == function-* ]]; then
                function_definition_inputs[$scope]=$input_source
            elif (( function_depth )) && [[ $kind != paren ]]; then
                record_credential_function_effect "$scope" input "$scope" "$input_source" 0
                scope_replay_inputs[$scope]=scope:$scope
            fi
            bind_credential_input
            open_credential_stream
            scope_inputs[$scope]=$input_source
        elif (( pipeline_next )) && [[ $kind != function-* ]]; then
            scope_inputs[$scope]=${pipeline_input:-unknown}
        fi
    fi
    if [[ -n ${scope_sites[$scope]:-} && $kind != function-* ]]; then
        local key descriptor binding site=${scope_sites[$scope]}
        local -A opened=()
        local -A descriptor_bindings=()
        if (( entered_redirections )); then
            for descriptor in "${!redirected_fds[@]}"; do
                descriptor_bindings[$descriptor]=${redirected_fds[$descriptor]}
            done
        else
            for key in "${!compound_descriptor_inputs[@]}"; do
                [[ $key == "$site:"* ]] || continue
                descriptor_bindings[${key##*:}]=${compound_descriptor_inputs[$key]}
            done
        fi
        for descriptor in "${!descriptor_bindings[@]}"; do
            binding=${descriptor_bindings[$descriptor]}
            input_source=$binding
            if [[ $binding == stream:* ]] && (( ! entered_redirections )); then
                if [[ ${opened[$binding]+present} ]]; then input_source=${opened[$binding]}
                else
                    input_source=bytes:${compound_stream_contents[$site:$binding]}
                    open_credential_stream
                    opened[$binding]=$input_source
                fi
            else
                bind_credential_input
                open_credential_stream
            fi
            if [[ $descriptor == 0 ]]; then scope_inputs[$scope]=$input_source
            else scope_descriptors[$scope:$descriptor]=$input_source; fi
        done
    fi
    isolated_scopes+=("$scope")
    credential_scopes+=("$kind")
    case $kind in brace|paren|loop|if|case) scope_stdout[$scope]=literal: ;; esac
    case $kind in
        brace|if|loop|case)
            # The closing token determines whether this is also a left-hand segment.
            compound_scopes[$scope]=$((pipeline_next && (! lastpipe || monitor_mode)))
            ;;
    esac
    pipeline_next=0
    if [[ $kind == function-* ]]; then
        # Parenthesized function bodies execute in a subshell at call time.
        if [[ $kind == function-paren ]]; then
            function_scopes[$scope]=2
        else
            function_scopes[$scope]=1
        fi
        if (( ! dead_branches )); then
            # Publish the summary only after scanning the body.
            definition_names[$scope]=$function_body_pending
        fi
        function_depth=$scope
        definition_starts[$scope]=$logical_start:$(($2 + 1))
        reset_credential_definition_positionals
    fi
}

reset_credential_definition_positionals() {
    local replaying_function=1
    change_credential_state positional_empty 0
    change_credential_state positional_bounds ''
}

leave_credential_scope() {
    local scope=${isolated_scopes[-1]} key entry parent parent_entry keep=0 keep_files=${2:-0}
    local -a writes=()
    (( ${#credential_scopes[@]} )) || return 0
    case ${credential_scopes[-1]} in
        "$1"|"function-$1") ;;
        *) return 0 ;;
    esac
    if (( discovering_credential_inputs )) && [[ -n ${scope_sites[$scope]:-} ]]; then
        local command_input='' position=$position redirect_start=$position
        local command_redirection_failed=0 suppress_command_writes=0
        local -A command_outputs=() command_fds=()
        collect_credential_compound_redirections 1
        compound_redirections[${scope_sites[$scope]}]=${source:redirect_start:position-redirect_start}
        compound_redirection_origins[${scope_sites[$scope]}]=$logical_start:$redirect_start
        if [[ -n $command_input ]]; then compound_inputs[${scope_sites[$scope]}]=$command_input; fi
        local descriptor binding site=${scope_sites[$scope]}
        for descriptor in "${!command_fds[@]}"; do
            binding=${command_fds[$descriptor]}
            if [[ $binding == stream:* ]]; then
                compound_stream_contents[$site:$binding]=${credential_streams[${binding#stream:}]}
            fi
            compound_descriptor_inputs[$site:$descriptor]=$binding
        done
    fi
    clear_credential_abort "$scope" 1
    unset 'isolated_scopes[-1]'
    parent=${isolated_scopes[-1]}
    if [[ ${compound_scopes[$scope]+present} ]]; then
        if credential_compound_detached; then
            if [[ -n ${scope_sites[$scope]:-} ]]; then detached_compound_sites[${scope_sites[$scope]}]=1; fi
        elif (( ! compound_scopes[$scope] )); then
            keep=1
            if records_credential_effects "$parent"; then
                function_writes[$parent]+=${function_writes[$scope]:-}
            fi
        fi
    fi
    if [[ -n ${scope_sites[$scope]:-} && ${position+x} ]] &&
            ! credential_compound_detached background &&
            [[ ${credential_scopes[-1]} == paren || ${compound_scopes[$scope]+present} ]]; then
        keep_files=1
    fi
    IFS=' ' read -r -a writes <<< "${scope_writes[$scope]:-}"
    for key in "${writes[@]}"; do
        entry=$scope:$key
        if (( ! keep )) && ! { (( keep_files )) && [[ $key == file:* ]]; }; then
            restore_credential_state "$key" "${saved_state[$entry]}"
        elif (( parent )) && [[ $parent != "${positional_call_scope:-}" || $key != positional_* ]]; then
            parent_entry=$parent:$key
            if [[ ! ${saved_state[$parent_entry]+present} ]]; then
                saved_state[$parent_entry]=${saved_state[$entry]}
                scope_writes[$parent]+=" $key"
            fi
            if [[ ${local_facts[$entry]+present} ]]; then local_facts[$parent_entry]=1; fi
        fi
        unset 'saved_state[$entry]' 'local_facts[$entry]'
    done
    unset 'scope_writes[$scope]' 'compound_scopes[$scope]' 'scope_sites[$scope]' 'scope_inputs[$scope]' 'scope_replay_inputs[$scope]'
    if [[ ${credential_scopes[-1]} != function-* ]]; then unset 'function_writes[$scope]'; fi
    if [[ ${credential_scopes[-1]} == function-* ]]; then
        definition_ends[$scope]=$logical_start:$((position - 1))
        if [[ ${function_replay_bodies[$scope]+present} ]]; then
            # Redirected inner constructs depend on call-time descriptors,
            # cwd and file facts. Replay the body instead of freezing writes.
            function_writes[$scope]=''
            record_credential_function_effect "$scope" loop "${definition_starts[$scope]}" "${definition_ends[$scope]}" 0
            conditional_function_returns[$scope]=1
        fi
        if [[ ${returned_functions[$scope]+present} ]]; then dead_branches=$((dead_branches - 1)); fi
        if [[ ${definition_names[$scope]+present} ]]; then
            change_credential_state "function:${definition_names[$scope]}" "$scope"
        fi
        function_depth=0
        for scope in "${isolated_scopes[@]}"; do
            if [[ ${function_scopes[$scope]+present} ]]; then function_depth=$scope; fi
        done
    fi
    case ${credential_scopes[-1]} in
        brace|paren|loop|if|case) compound_stdout=${scope_stdout[$scope]:-unknown} ;;
    esac
    if [[ ${failed_compound_scopes[$scope]+present} ]]; then
        dead_branches=$((dead_branches - 1))
        command_redirection_failed=1
        compound_stdout=literal:
        unset 'failed_compound_scopes[$scope]'
    fi
    # Entry-time opens must not be repeated at the trailing syntax: under
    # noclobber even the file we just created would reject a second open.
    # Enclosed commands have already written through their stdout binding.
    if [[ ${credential_scopes[-1]} == function-* || ${opened_compound_redirections[$scope]+present} ]]; then
        collect_credential_compound_redirections
        unset 'opened_compound_redirections[$scope]'
    fi
    unset 'credential_scopes[-1]'
}

credential_compound_detached() {
    local position=$position
    collect_credential_compound_redirections
    case ${source:position:2} in
        '||'|'&&') return 1 ;;
        '|'*) [[ ${1:-} != background ]]; return ;;
        '&'*) return 0 ;;
    esac
    return 1
}

collect_credential_compound_redirections() {
    local scan_word_substitutions=0
    while :; do
        while [[ ${source:position:1} == [[:blank:]] ]]; do position=$((position + 1)); done
        skip_credential_redirection "${1:-0}" || break
    done
}

resolve_credential_directory() (
    local destination=$1 mode=${2:-L}
    if [[ $destination != /* ]]; then
        [[ -n $credential_directory ]] && cd -- "$credential_directory" || return 1
    fi
    CDPATH= cd "$mode" -- "$destination" 2>/dev/null && pwd "$mode"
)

apply_credential_cd() {
    local destination=$1 directory=$2 scope=${isolated_scopes[-1]}
    (( ! dead_branches )) || return 0
    if (( ! replaying_function )) && records_credential_effects "$scope"; then
        record_credential_function_effect "$scope" cd "$destination" "${3:-L}" 0
    fi
    if [[ -n $destination && -z $directory ]]; then return 0; fi
    local replaying_function=1
    change_credential_state fact:OLDPWD unknown
    change_credential_state credential_directory "$directory"
}

# Recursively reduce known facts to integers, with cycle and depth guards.
# Only arithmetic tokens reach Bash; shell commands are never evaluated.
emit_credential_arithmetic_abort() {
    (( arithmetic_exit_emit )) || return 0
    local slot outcome=${1:-ABORT}
    if (( arithmetic_exit_bad_index )); then outcome=FAIL; fi
    if [[ $outcome == FAIL ]]; then printf '0\n'; else printf '\n'; fi
    for slot in "${!arithmetic_exit_values[@]}"; do
        if [[ ${arithmetic_exit_touched[slot]:-0} == 1 && ${arithmetic_exit_targets[slot]} != *'[invalid]' ]]; then
            printf '%s\t%s\n' "${arithmetic_exit_targets[slot]}" "${arithmetic_exit_values[slot]}"
        fi
    done
    printf '%s\n' "$outcome"
}

credential_arithmetic_value() {
    local remaining=$1 expression='' token name value
    local emit_writes=${arithmetic_emit_writes:-0} arithmetic_emit_writes=0
    local arithmetic_names=${2:-:} arithmetic_depth=${3:-0}
    (( arithmetic_depth < 32 )) || return 1
    local space='^[[:space:]]+' number='^([0-9]+#[[:alnum:]@_]+|0[xX][[:xdigit:]]+|[0-9]+)'
    local reference='^(([$])?([A-Za-z_][A-Za-z0-9_]*)|[$]([0-9#])|[$][{]([A-Za-z_][A-Za-z0-9_]*|[0-9]+|#)[}])'
    local current
    local integer='^[+-]?(0[xX][[:xdigit:]]+|[0-9]+)$'
    local operator='^([+][+]|--|<<=|>>=|[-+*/%&|^]=|\*\*|==|!=|<=|>=|<<|>>|&&|\|\||[-+*/%()<>!~&|^?:,=])'
    local -a arithmetic_values=() arithmetic_targets=() arithmetic_touched=()
    local -A arithmetic_slots=() arithmetic_unknown=()
    local slot suffix slot_key slot_reference resolved_subscript subscript_value
    local subscript='^\[[^]]*\]'
    local -a arithmetic_readonly=()
    local assignment_operator='^[[:space:]]*(<<=|>>=|[-+*/%&|^]=|=[^=]|=$)'
    local writes=0 is_assignment=0 recursive_fact=0 previous_token=''
    local increment_suffix='^[[:space:]]*([+][+]|--)'
    (( ${#remaining} <= 256 )) || return 1
    local expanded='' substitution_reference opening candidate fact
    local simple_parameter='^[$]([A-Za-z_][A-Za-z0-9_]*|[0-9#])|^[$][{]([A-Za-z_][A-Za-z0-9_]*|[0-9]+|#)[}]'
    local whole_array='^\$\{([A-Za-z_][A-Za-z0-9_]*)\[([@*])\]\}'
    while [[ -n $remaining ]]; do
        if [[ $remaining =~ $simple_parameter ]]; then
            # Shell parameter expansion precedes arithmetic assignments. Only
            # bare arithmetic names refer to the mutable evaluation slots.
            token=${BASH_REMATCH[0]}
            name=${BASH_REMATCH[1]:-${BASH_REMATCH[2]}}
            credential_parameter_fact "$name" ''
            if [[ $current == unset ]]; then current=scalar:; fi
            [[ $current == scalar:* ]] || return 1
            value=${current#scalar:}
            [[ $value != *'$'* && $value != *'`'* ]] || return 1
            expanded+=$value
            remaining=${remaining:${#token}}
            continue
        fi
        if [[ $remaining == '$('* && $remaining != '$(('* || $remaining == '`'* ]]; then
            # Reuse the parser's boundaries, including nested producers. Only
            # modeled stdout is inserted; source commands are never executed.
            substitution_reference=''
            for opening in "${!substitution_ends[@]}" "${!backtick_ends[@]}"; do
                if [[ $remaining == '`'* ]]; then
                    [[ -n ${backtick_ends[opening]:-} ]] || continue
                    candidate=${logical:opening:backtick_ends[opening]+1-opening}
                else
                    [[ -n ${substitution_ends[opening]:-} ]] || continue
                    candidate=${logical:opening:substitution_ends[opening]+1-opening}
                fi
                if [[ $remaining == "$candidate"* && ${#candidate} -gt ${#substitution_reference} ]]; then
                    substitution_reference=$candidate
                fi
            done
            [[ -n $substitution_reference ]] || return 1
            fact=${substitution_values[$substitution_reference]:-unknown}
            if (( ${skip_expansion_effects:-0} )) || [[ $fact != scalar:* ]]; then
                fact=$(credential_substitution_fact "$substitution_reference") || return 1
            fi
            value=${fact#scalar:}
            [[ $value != *'$'* && $value != *'`'* ]] || return 1
            expanded+=$value
            remaining=${remaining:${#substitution_reference}}
            continue
        elif [[ $remaining != '${'* ]]; then
            # Double quotes protect shell expansions, but are not arithmetic
            # tokens. Keep the substituted text's operator precedence intact.
            if [[ ${remaining:0:1} != '"' ]]; then expanded+=${remaining:0:1}; fi
            remaining=${remaining:1}
            continue
        fi
        local source=$remaining position=2 parameter_value=unknown pattern_list_subject=1
        local pattern_list_known=0
        local -a pattern_list_words=()
        if [[ $remaining =~ $whole_array ]]; then
            # Associative elements are stored by key, not in the ordered bounds
            # used here. Their placeholder bounds do not prove an empty array.
            [[ ${variable_facts[${BASH_REMATCH[1]}]:-unknown} != assoc-array:* ]] || return 1
            position=${#BASH_REMATCH[0]}
            resolve_credential_pattern_list "${BASH_REMATCH[1]}" '#' '' '' "[${BASH_REMATCH[2]}]"
        else
            read_parameter_word 0 0 0 1 || return 1
        fi
        if (( pattern_list_known )); then
            value=''
            local element separator=''
            for element in "${pattern_list_words[@]}"; do
                value+=$separator$element
                separator=' '
            done
        else
            [[ $parameter_value == scalar:* ]] || return 1
            value=${parameter_value#scalar:}
        fi
        [[ $value != *'$'* && $value != *'`'* ]] || return 1
        expanded+=$value
        remaining=${remaining:position}
    done
    remaining=$expanded$remaining
    (( ${#remaining} <= 4096 )) || return 1
    local arithmetic_expression='' arithmetic_unresolved=0
    local -a arithmetic_indices=()
    compile_credential_arithmetic "$remaining" || return 1
    expression=$arithmetic_expression
    # Recursive variable expressions can observe writes earlier in the same
    # expression. Do not freeze them to their entry values in that case.
    (( ! writes || ! recursive_fact )) || return 1
    if [[ -z $expression ]]; then printf 0; return 0; fi
    # A fully known expression with invalid syntax cannot take the branch.
    (
        # Use internal readonly slots so Bash stops at the rejected write,
        # retaining earlier effects without unwinding the modeled script.
        set +e
        arithmetic_readonly=("${arithmetic_values[@]}")
        readonly -a arithmetic_readonly
        unset value
        # Bash unwinds function locals before an EXIT trap on a fatal indexed
        # write. These generated slots live only in this isolated subprocess,
        # but survive that unwind so the trap can report partial effects.
        declare -g arithmetic_exit_emit=$emit_writes arithmetic_exit_bad_index=0
        declare -ga arithmetic_exit_values=("${arithmetic_values[@]}")
        declare -ga arithmetic_exit_targets=("${arithmetic_targets[@]}")
        declare -ga arithmetic_exit_touched=()
        expression=${expression//arithmetic_values/arithmetic_exit_values}
        expression=${expression//arithmetic_touched/arithmetic_exit_touched}
        # A readonly write in a nested subscript can terminate this evaluator
        # before the next statement. Its EXIT trap retains the preceding writes
        # and distinguishes that fatal unwind from an unresolved expression.
        trap emit_credential_arithmetic_abort EXIT
        (( value = expression )) || :
        trap - EXIT
        arithmetic_values=("${arithmetic_exit_values[@]}")
        for slot in "${!arithmetic_exit_touched[@]}"; do
            arithmetic_touched[slot]=${arithmetic_exit_touched[slot]}
        done
        if [[ ! ${value+x} ]]; then
            # Reaching this statement means Bash recovered from an ordinary
            # arithmetic error. Only the EXIT trap reports a fatal subscript.
            emit_credential_arithmetic_abort FAIL
            exit 2
        fi
        if (( arithmetic_unresolved )); then exit 1; fi
        printf '%s' "$value"
        if (( emit_writes && writes )); then
            printf '\n'
            for slot in "${!arithmetic_values[@]}"; do
                if [[ ${arithmetic_touched[slot]:-0} == 1 && ${arithmetic_targets[slot]} != *'[invalid]' ]]; then
                    printf '%s\t%s\n' "${arithmetic_targets[slot]}" "${arithmetic_values[slot]}"
                fi
            done
        fi
        if (( emit_writes )); then printf '\nOK\n'; fi
    ) 2>/dev/null
}

# Compile nested index expressions against the same mutable arithmetic slots.
# Only generated slot references and validated arithmetic tokens are evaluated.
compile_credential_arithmetic() {
    local remaining=$1 expression='' token name value current suffix index
    local slot slot_key slot_reference resolved_subscript subscript_value
    local is_assignment=0 previous_token='' target_name
    local arithmetic_depth=$((arithmetic_depth + 1))
    (( arithmetic_depth < 32 )) || return 1
    while [[ -n $remaining ]]; do
        if [[ $remaining =~ $space ]]; then
            token=${BASH_REMATCH[0]}
        elif [[ $remaining =~ $number || $remaining =~ $operator ]]; then
            token=${BASH_REMATCH[0]}
            expression+=" $token"
            previous_token=$token
        elif [[ $remaining =~ $reference ]]; then
            token=${BASH_REMATCH[0]}
            name=${BASH_REMATCH[3]:-${BASH_REMATCH[4]:-${BASH_REMATCH[5]:-}}}
            suffix=${remaining:${#token}}
            local target_name=$name index=''
            if [[ $suffix == '['* ]]; then
                local bracket_depth=0 bracket_end bracket_char
                for (( bracket_end=0; bracket_end<${#suffix}; bracket_end++ )); do
                    bracket_char=${suffix:bracket_end:1}
                    case $bracket_char in
                        '[') bracket_depth=$((bracket_depth + 1)) ;;
                        ']') bracket_depth=$((bracket_depth - 1)); (( bracket_depth )) || break ;;
                    esac
                done
                (( bracket_end < ${#suffix} )) || return 1
                index=${suffix:0:bracket_end+1}
                token+=$index
                suffix=${suffix:${#index}}
            fi
            resolve_credential_nameref || return 1
            if [[ -n $index ]]; then
                if [[ ${variable_facts[$name]:-} == assoc-array:* ]]; then
                    resolve_credential_subscript "$name" "$index"
                    index=$resolved_subscript
                    [[ -n $index ]] || return 1
                else
                    if [[ ! $index =~ ^\[(0|[1-9][0-9]*)\]$ ]]; then
                        local element_selector='' element_index_slot target_base=$name
                        local target_write=0
                        if [[ $suffix =~ $assignment_operator || $suffix =~ $increment_suffix ||
                                $previous_token == @('++'|'--') ]]; then target_write=1; fi
                        if [[ $suffix =~ ^[[:space:]]*=([^=]|$) ]]; then target_write=2; fi
                        compile_credential_arithmetic_element "$name" "${index:1:-1}" "$target_write" || return 1
                        slot_reference=$element_selector
                        if (( target_write )); then
                            writes=1
                            if [[ ${readonly_facts[$target_base]:-0} != 1 ]]; then
                                # Evaluate an index with side effects only once.
                                slot_reference="(arithmetic_indices[$element_index_slot]=$element_selector,arithmetic_touched[arithmetic_indices[$element_index_slot]]=1,arithmetic_indices[$element_index_slot])"
                            fi
                        fi
                        if (( target_write )) && [[ ${readonly_facts[$target_base]:-0} == 1 ]]; then
                            expression+=" arithmetic_readonly[$slot_reference]"
                        else expression+=" arithmetic_values[$slot_reference]"; fi
                        previous_token=''
                        remaining=$suffix
                        continue
                    fi
                fi
            elif [[ ${variable_facts[$name]:-} == @(array:*|partial-array:*|assoc-array:*) ]]; then
                index='[0]'
            fi
            slot_key=$name$index
            is_assignment=0
            if [[ $suffix =~ $assignment_operator || $suffix =~ $increment_suffix ||
                    $previous_token == @('++'|'--') ]]; then
                is_assignment=1
                [[ $token == "$target_name"* ]] || return 1
                writes=1
            fi
            previous_token=''
            if [[ ${arithmetic_slots[$slot_key]+present} ]]; then
                slot=${arithmetic_slots[$slot_key]}
                if [[ ${arithmetic_unknown[$slot_key]+present} ]] &&
                        ! [[ $suffix =~ ^[[:space:]]*=([^=]|$) ]]; then return 1; fi
                slot_reference=$slot
                if (( is_assignment )) && [[ ${readonly_facts[$name]:-0} != 1 ]]; then
                    slot_reference="(arithmetic_touched[$slot]=1,$slot)"
                fi
                if (( is_assignment )) && [[ ${readonly_facts[$name]:-0} == 1 ]]; then
                    expression+=" arithmetic_readonly[$slot]"
                else expression+=" arithmetic_values[$slot_reference]"; fi
                remaining=$suffix
                continue
            fi
            credential_parameter_fact "$name" "$index" 1
            value=$current
            # A plain assignment does not read the old value.
            if [[ $value != @(scalar:*|unset) && $suffix =~ ^[[:space:]]*=([^=]|$) ]]; then
                arithmetic_unknown[$slot_key]=1
                value=scalar:0
            fi
            if [[ $value == unset ]]; then value=scalar:0; fi
            [[ $value == scalar:* ]] || return 1
            value=${value#scalar:}
            if [[ ! $value =~ $integer ]]; then
                if [[ $value == *[A-Za-z_]* ]]; then recursive_fact=1; fi
                [[ $arithmetic_names != *":$name:"* ]] || return 1
                value=$(credential_arithmetic_value "$value" "$arithmetic_names$name:" "$((arithmetic_depth + 1))") || return 1
            fi
            slot=${#arithmetic_values[@]}
            arithmetic_slots[$slot_key]=$slot
            arithmetic_targets[slot]=$slot_key
            arithmetic_values[slot]=$value
            slot_reference=$slot
            if (( is_assignment )) && [[ ${readonly_facts[$name]:-0} != 1 ]]; then
                slot_reference="(arithmetic_touched[$slot]=1,$slot)"
            fi
            if (( is_assignment )) && [[ ${readonly_facts[$name]:-0} == 1 ]]; then
                expression+=" arithmetic_readonly[$slot]"
            else expression+=" arithmetic_values[$slot_reference]"; fi
        else
            return 1
        fi
        remaining=${remaining:${#token}}
    done
    arithmetic_expression=$expression
}

# Map runtime indexed-array keys to fact slots. An unmodeled element leaves
# the expression unknown; it must not be mistaken for a known shell failure.
compile_credential_arithmetic_element() {
    local array_name=$1 index_expression=$2 target_write=$3
    local arithmetic_expression='' subexpression selector='' candidate slot_key slot
    local bounds=${variable_facts[$1]:-unknown} offset
    local -A candidates=()
    compile_credential_arithmetic "$index_expression" || return 1
    subexpression=$arithmetic_expression
    local scratch=${#arithmetic_indices[@]}
    arithmetic_indices+=(0 0)
    case $bounds in
        array:*|partial-array:*)
            bounds=${bounds#*:}
            for (( offset=${bounds%:*}; offset<${bounds#*:}; offset++ )); do
                candidates[${known_array_indices[offset]}]=1
            done
            ;;
        scalar:*|unset) candidates[0]=1 ;;
    esac
    # Also cover a statically resolvable new index in a sparse array.
    if candidate=$(credential_arithmetic_value "$index_expression" "$arithmetic_names" "$((arithmetic_depth + 1))") &&
            [[ $candidate =~ ^[0-9]+$ ]]; then candidates[$candidate]=1; fi
    for candidate in "${!candidates[@]}"; do
        if (( target_write == 2 )); then
            compile_credential_arithmetic "$array_name[$candidate]=0" || return 1
        else compile_credential_arithmetic "$array_name[$candidate]" || return 1; fi
        slot_key=$array_name[$candidate]
        slot=${arithmetic_slots[$slot_key]}
        selector+="arithmetic_indices[$scratch]==$candidate ? $slot : "
    done
    local array_extent normalization=''
    if credential_array_extent "$array_name"; then
        normalization="arithmetic_indices[$scratch]<0 ? arithmetic_indices[$scratch]+=$array_extent : 0,"
        # A known invalid write must fail without touching an unrelated slot.
        # Reads of an invalid negative element expand to zero in arithmetic.
        local missing_slot=${#arithmetic_values[@]} missing_value
        arithmetic_values[missing_slot]=0
        arithmetic_targets[missing_slot]="$array_name[invalid]"
        if (( target_write )); then
            missing_value="(arithmetic_exit_bad_index=1,arithmetic_readonly[0]=0)"
        else missing_value=$missing_slot; fi
        selector="arithmetic_indices[$scratch]<0 ? $missing_value : $selector"
    fi
    element_selector="(arithmetic_indices[$scratch]=($subexpression),$normalization$selector (arithmetic_unresolved=1,0))"
    element_index_slot=$((scratch + 1))
}

# Expand only literal data, in the modeled directory, without evaluating it.
# The final NUL record distinguishes success from an unavailable directory.
expand_credential_let_globs() (
    local IFS='' field
    local -a matches=()
    [[ -n $credential_directory ]] || return 1
    cd -- "$credential_directory" || return 1
    unset GLOBIGNORE
    set +f
    shopt -u failglob nullglob dotglob nocaseglob
    if (( nullglob )); then shopt -s nullglob; fi
    if (( dotglob )); then shopt -s dotglob; fi
    for field; do
        matches=( $field )
        if (( ${#matches[@]} )); then printf '%s\0' "${matches[@]}"; fi
    done
    printf '\2\0'
)

# let evaluates each expanded argv word separately. Quoted @ expansions
# attach a prefix to the first element and a suffix to the last element.
resolve_credential_let_words() {
    local input=$literal chunk reference offset quoted current parameter_value
    local pattern_list_known pattern_list_subject=1 any=0
    local -a pattern_list_words=()
    local at_reference='^\$\{(@|[A-Za-z_][A-Za-z0-9_]*\[@\])([#%/]|\})'
    (( ! word_glob && ! word_constructed )) || return 1
    let_words=('')
    while [[ -n $input ]]; do
        if [[ $input =~ $env_expansion_ref ]]; then
            offset=${BASH_REMATCH[1]}
            input=${input:${#BASH_REMATCH[0]}}
            reference=${env_expansions[offset]}
            quoted=${env_expansion_quotes[offset]:-}
            pattern_list_known=0 pattern_list_words=()
            if [[ $reference =~ $at_reference ]]; then
                if [[ $reference == *'[@]}' || $reference == '${@}' ]]; then
                    local name=${reference:2:-1} index=''
                    if [[ $name != @ ]]; then index='[@]'; name=${name%\[@\]}; fi
                    resolve_credential_pattern_list "$name" '#' '' '' "$index"
                else
                    local source=$reference position=2
                    read_parameter_word 0 0 0 1 || return 1
                fi
                (( pattern_list_known )) || return 1
                if [[ $quoted != '"' ]]; then
                    # Mixed quoted/unquoted concatenations need quote masks;
                    # retain unknown facts unless this is a standalone list.
                    [[ $word == "$reference" ]] || return 1
                    local separator=${variable_facts[IFS]-scalar:$' \t\n'}
                    [[ $separator == scalar:* ]] || return 1
                    mapfile -d '' -t pattern_list_words < <(
                        split_credential_fields "${separator#scalar:}" '@' "${pattern_list_words[@]}"
                    )
                    if (( ! noglob )); then
                        mapfile -d '' -t pattern_list_words < <(
                            expand_credential_let_globs "${pattern_list_words[@]}"
                        )
                        (( ${#pattern_list_words[@]} )) && [[ ${pattern_list_words[-1]} == $'\2' ]] || return 1
                        unset 'pattern_list_words[-1]'
                    fi
                fi
                if (( ${#pattern_list_words[@]} )); then
                    let_words[-1]+=${pattern_list_words[0]}
                    let_words+=("${pattern_list_words[@]:1}")
                    any=1
                fi
            else
                local literal="$env_expansion_marker$offset$env_expansion_marker"
                resolve_env_word 1 0 1 || return 1
                # Unknown field splitting or globbing retains unknown facts.
                if [[ $quoted != '"' && $literal == *[[:space:]*?\[]* ]]; then return 1; fi
                let_words[-1]+=$literal
                any=1
            fi
        else
            chunk=${input%%"$env_expansion_marker"*}
            [[ -n $chunk ]] || return 1
            let_words[-1]+=$chunk
            input=${input:${#chunk}}
            any=1
        fi
    done
    if (( ! any && word_expansion )); then let_words=(); fi
}

# An unresolved arithmetic expression may still have performed writes. Drop
# their old facts instead of treating failure to evaluate as absence of effects.
invalidate_credential_arithmetic_writes() {
    local input=$1 token name index increment subscript_value
    local target='^(([+][+]|--)[[:space:]]*)?([A-Za-z_][A-Za-z0-9_]*)(\[[^]]*\])?'
    local assignment='^[[:space:]]*(<<=|>>=|[-+*/%&|^]=|=[^=]|=$|[+][+]|--)'
    while [[ -n $input ]]; do
        if [[ $input =~ $target ]]; then
            token=${BASH_REMATCH[0]} increment=${BASH_REMATCH[2]}
            name=${BASH_REMATCH[3]} index=${BASH_REMATCH[4]}
            input=${input:${#token}}
            if [[ -n $index && ${variable_facts[$name]:-} != assoc-array:* ]]; then
                invalidate_credential_arithmetic_writes "${index:1:-1}"
            fi
            if [[ -n $increment || $input =~ $assignment ]]; then
                resolve_credential_nameref || continue
                if [[ -n $index && ${variable_facts[$name]:-} != assoc-array:* ]]; then
                    if subscript_value=$(credential_arithmetic_value "${index:1:-1}"); then
                        index=[$subscript_value]
                    fi
                fi
                if [[ -n $index ]]; then assign_credential_element "$name" "$index" unknown
                else change_credential_state "fact:$name" unknown; fi
            fi
        else
            input=${input:1}
        fi
    done
}

apply_credential_arithmetic_assignment() {
    local expression=$1 name operator rhs fact
    arithmetic_assignment_result=''
    arithmetic_assignment_failed=0
    arithmetic_assignment_fatal=0
    local prior_result=''
    local -a evaluated=()
    mapfile -t evaluated < <(arithmetic_emit_writes=1 credential_arithmetic_value "$expression")
    if (( ${#evaluated[@]} )) && [[ ${evaluated[-1]} == @(OK|ABORT|FAIL) ]]; then
        arithmetic_assignment_result=${evaluated[0]}
        if [[ ${evaluated[-1]} != OK ]]; then arithmetic_assignment_failed=1; fi
        if [[ ${evaluated[-1]} == ABORT ]]; then arithmetic_assignment_fatal=1; fi
        local update index
        for update in "${evaluated[@]:1:${#evaluated[@]}-2}"; do
            [[ -n $update ]] || continue
            name=${update%%$'\t'*} index=''
            if [[ $name == *'['* ]]; then index=[${name#*\[}; name=${name%%\[*}; fi
            if [[ -n $index ]]; then assign_credential_element "$name" "$index" "scalar:${update#*$'\t'}" 0 0 1
            else change_credential_state "fact:$name" "scalar:${update#*$'\t'}"; fi
        done
        return 0
    fi
    local prefix='^[[:space:]]*'
    local target='([A-Za-z_][A-Za-z0-9_]*)'
    local suffix='[[:space:]]*$'
    if [[ $expression =~ ${prefix}${target}(\+\+|--)${suffix} ]]; then
        name=${BASH_REMATCH[1]} operator=${BASH_REMATCH[2]}
        prior_result=$(credential_arithmetic_value "$name") || prior_result=''
        rhs="$name ${operator:0:1} 1"
    elif [[ $expression =~ ${prefix}(\+\+|--)${target}${suffix} ]]; then
        operator=${BASH_REMATCH[1]} name=${BASH_REMATCH[2]}
        rhs="$name ${operator:0:1} 1"
    elif [[ $expression =~ ${prefix}${target}[[:space:]]*([-+*/%]?)=([^=].*)$ ]]; then
        name=${BASH_REMATCH[1]} operator=${BASH_REMATCH[2]} rhs=${BASH_REMATCH[3]}
        if [[ -n $operator ]]; then rhs="$name $operator ( $rhs )"; fi
        invalidate_credential_arithmetic_writes "$expression"
    else
        invalidate_credential_arithmetic_writes "$expression"
        return 0
    fi
    rhs='$(( '$rhs' ))'
    resolve_credential_assignment "$rhs"
    local index=''
    resolve_credential_nameref || return 0
    if [[ ${readonly_facts[$name]:-0} == 1 ]]; then
        arithmetic_assignment_failed=1 arithmetic_assignment_fatal=1
        return 0
    fi
    if [[ $fact == scalar:* ]]; then arithmetic_assignment_result=${prior_result:-${fact#scalar:}}; fi
    if [[ -n $index ]]; then assign_credential_element "$name" "$index" "$fact"
    else change_credential_state "fact:$name" "$fact" 0 "$rhs"
    fi
}

resolve_credential_assignment() {
    local source=$1 position=0 literal='' word_expansion=0
    local -a env_expansions=() env_expansion_quotes=()
    fact=unknown
    if [[ $source == '"$(('*'))"' ]]; then
        source=${source:1:${#source}-2}
    fi
    if [[ $source == '$(('*'))' ]]; then
        if literal=$(credential_arithmetic_value "${source:3:${#source}-5}"); then
            fact=scalar:$literal
        fi
        return 0
    fi
    read_shell_word '' 0 2
    if (( ! word_expansion )) || resolve_env_word 1; then
        fact=scalar:$literal
    fi
}

replay_credential_input() {
    case $input_source in
        function-input) input_source=$call_input ;;
        scope:*) input_source=${bound_inputs[${input_source#scope:}]:-unknown} ;;
    esac
}

save_credential_caller_options() {
    local option
    if (( ! ${#caller_options[@]} )); then
        for option in noglob braceexpand noclobber monitor_mode; do
            caller_options[$option]=${!option}
        done
    fi
}

# Give replayed loop returns a function boundary while retaining the
# caller's facts and the same stream cursor used by the rest of the call.
replay_credential_function_loop() {
    local pipeline_next=0 reached_loop_return=0 source='' position=0
    enter_credential_scope brace
    local function_depth=${isolated_scopes[-1]} replaying_loop_function=${isolated_scopes[-1]}
    scope_inputs[$function_depth]=$3
    local descriptor
    for descriptor in "${!function_call_fds[@]}"; do
        scope_descriptors[$function_depth:$descriptor]=${function_call_fds[$descriptor]}
    done
    scan_credential_range "$1" "$2"
    if (( reached_loop_return )); then dead_branches=$((dead_branches - 1)); fi
    leave_credential_scope brace
    (( reached_loop_return ))
}

apply_credential_function() {
    local callee=$1 pipeline_call=$2 detached_call=${4:-0} scope=${isolated_scopes[-1]} effect key fact deferred name
    local summary=${credential_functions[$1]:-} call_input=$3 input_source
    local option positional_empty=0 positional_bounds=${5:-}
    local positional_call_scope=$scope
    if [[ -n $positional_bounds && ${positional_bounds%:*} == "${positional_bounds#*:}" ]]; then
        positional_empty=1
    fi
    local -A caller_options=() bound_inputs=()
    local -a writes=()
    local -A caller_facts=() caller_readonly=() caller_attributes=() caller_integer=()
    (( ! dead_branches )) || return 0
    if (( ! replaying_function )) && records_credential_effects "$scope"; then
        record_credential_function_effect "$scope" call "$callee" "$pipeline_call" 0
        credential_input_source 1
        function_call_inputs[next_function_effect]=$input_source
        function_call_detached[next_function_effect]=$detached_call
        function_call_positionals[next_function_effect]=$positional_bounds
        if [[ -n $summary && ${function_may_abort[$summary]+present} ]]; then
            function_may_abort[$function_depth]=1
        fi
    fi
    if [[ -z $summary || $active_function_calls == *":$summary:"* ]]; then return 0; fi
    local redirection_failed=0 descriptor
    local -A redirected_fds=() function_call_fds=()
    inspect_credential_redirections "${function_definition_redirections[$summary]:-}" "${function_definition_redirection_origins[$summary]:-}"
    if (( redirection_failed )); then builtin_status=1; return 0; fi
    for descriptor in "${!redirected_fds[@]}"; do
        input_source=${redirected_fds[$descriptor]}
        bind_credential_input
        open_credential_stream
        function_call_fds[$descriptor]=$input_source
    done
    local isolated_call=0
    if [[ ${function_scopes[$summary]} == 2 ]] || (( detached_call || (pipeline_call && (! lastpipe || monitor_mode)) )); then
        isolated_call=1
        enter_credential_scope paren
        positional_call_scope=${isolated_scopes[-1]}
    fi
    input_source=${function_call_fds[0]:-${function_definition_inputs[$summary]:-$call_input}}
    bind_credential_input
    open_credential_stream
    call_input=$input_source
    local replaying_function=1 active_function_calls=$active_function_calls$summary:
    local taken_return='' taken_abort='' diagnose_reports=0 diagnosed_readonly_abort=0
    # Summary replay has no function scope on the parser stack. Propagate
    # a rejected assignment through calls, then unwind the caller's frames.
    local replaying_readonly_call=1 readonly_call_aborted=0
    if (( ! discovering_credential_inputs && ! suppress_credential_reports &&
            (! function_depth || function_depth == diagnostic_function) )); then
        diagnose_reports=1
    fi
    if [[ ${definition_ends[$summary]+present} ]] &&
            (( diagnose_reports || ${conditional_function_returns[$summary]:-0} )); then
        local result value
        while IFS=' ' read -r result value; do
            case $result in
                return) taken_return=$value ;;
                abort) taken_abort=$value ;;
                readonly-abort) diagnosed_readonly_abort=1 ;;
                line) if (( diagnose_reports )); then report_credential_line "$value"; fi ;;
                call)
                    (( diagnose_reports )) || continue
                    if (( diagnostic_function )); then
                        printf 'call %s\n' "$value"
                    else
                        called_functions[$value]=1
                    fi
                    ;;
            esac
        done < <(diagnose_credential_function "$summary" "$call_input")
    fi
    local suppress_credential_reports=1
    read -r -a writes <<< "${function_writes[$summary]:-}"
    for effect in "${writes[@]}"; do
        key=${function_keys[effect]}
        fact=${function_effects[effect]}
        deferred=${function_values[effect]}

        if (( dead_branches || readonly_call_aborted )) || [[ $key == return ]]; then break; fi
        if [[ $key == conditional-abort ]]; then
            if [[ $taken_abort == "$fact" ]]; then
                abort_credential_shell "${logical_start:-0}:${position:-0}"
                break
            fi
            continue
        fi
        if [[ $key == conditional-return ]]; then
            if [[ $taken_return == "$fact" ]]; then break; fi
            continue
        fi
        if [[ $key == parameter ]]; then
            replay_credential_parameter "$effect" "$fact" "$deferred"
            continue
        fi
        if [[ $key == local-options ]]; then
            save_credential_caller_options
            continue
        fi
        if [[ $key == input ]]; then
            input_source=$deferred
            bind_credential_input
            open_credential_stream
            bound_inputs[$fact]=$input_source
            continue
        fi
        if [[ $key == call ]]; then
            input_source=${function_call_inputs[effect]}
            replay_credential_input
            apply_credential_function "$fact" "$deferred" "$input_source" \
                "${function_call_detached[effect]}" "${function_call_positionals[effect]}"
            if (( readonly_call_failed )); then readonly_call_aborted=1; fi
            continue
        fi
        if [[ $key == loop ]]; then
            if replay_credential_function_loop "$fact" "$deferred" "$call_input"; then break; fi
            continue
        fi
        if [[ $key == read ]]; then
            input_source=$fact
            replay_credential_input
            local read_status=0
            replay_credential_read "$effect" "$input_source"

            continue
        fi
        if [[ $key == output ]]; then
            apply_credential_output "$fact" "$deferred"
            continue
        fi
        if [[ $key == cd ]]; then
            local directory=''
            if [[ -n $fact ]]; then directory=$(resolve_credential_directory "$fact" "$deferred") || continue; fi
            apply_credential_cd "$fact" "$directory"
            continue
        fi
        if [[ -n $deferred ]]; then
            local append_effect=0
            if [[ $fact == append:* ]]; then append_effect=1; fi
            resolve_credential_assignment "$deferred"
            if (( append_effect )) && [[ $fact == scalar:* ]]; then fact=append:${fact#scalar:}; fi
        fi
        if (( function_locals[effect] )); then
            name=${key#*:}
            if [[ $key == attributes:* ]]; then
                if [[ ! ${caller_attributes[$name]+present} ]]; then
                    caller_attributes[$name]=${attribute_facts[$name]:-}
                fi
            elif [[ $key == integer:* ]]; then
                if [[ ! ${caller_integer[$name]+present} ]]; then
                    caller_integer[$name]=${integer_facts[$name]:-}
                fi
            elif [[ $key == readonly:* ]]; then
                if [[ ! ${caller_readonly[$name]+present} ]]; then
                    caller_readonly[$name]=${readonly_facts[$name]:-}
                fi
            elif [[ ! ${caller_facts[$name]+present} ]]; then
                caller_facts[$name]=${variable_facts[$name]:-}
            fi
            local value=$fact
            if [[ $key == fact:OPTIND ]] && (( ! ${applying_getopts:-0} )); then
                change_credential_state getopts_offset 1
            fi
            if [[ $key == fact:* ]]; then normalize_credential_assignment_fact "$name" ''; fi
            restore_credential_state "$key" "$value"
        else
            change_credential_state "$key" "$fact" 0 "$deferred" "${function_fatal_writes[effect]:-0}"
        fi
    done
    for option in "${!caller_options[@]}"; do
        change_credential_state "$option" "${caller_options[$option]}"
    done
    for name in "${!caller_facts[@]}"; do
        restore_credential_state "fact:$name" "${caller_facts[$name]}"
    done
    for name in "${!caller_attributes[@]}"; do
        restore_credential_state "attributes:$name" "${caller_attributes[$name]}"
    done
    for name in "${!caller_integer[@]}"; do
        restore_credential_state "integer:$name" "${caller_integer[$name]}"
    done
    for name in "${!caller_readonly[@]}"; do
        restore_credential_state "readonly:$name" "${caller_readonly[$name]}"
    done
    readonly_call_failed=$((readonly_call_aborted || diagnosed_readonly_abort))
    if (( readonly_call_failed )); then abort_credential_readonly; fi
    if (( isolated_call )); then
        leave_credential_scope paren "$((detached_call != 1))"
        readonly_call_failed=0
    fi
}

# Recognize a single literal condition, including one on its own logical line.
# A further condition command before the body makes the result unknown again.
observe_credential_if_word() {
    local delimiter='^[[:blank:]]*(;|$|'$'\n'')' truth
    case ${if_conditions[-1]} in
        waiting-command)
            if (( command_position )) && [[ -z $command_prefix && $word == '!' ]]; then
                if_negated[-1]=$(( ! if_negated[-1] ))
                return 0
            fi
            if_conditions[-1]=waiting-unknown
            if (( command_position )) && [[ -z $command_prefix && $word == read ]]; then
                if_conditions[-1]=waiting-read
                return 0
            fi
            if (( command_position )) && [[ -z $command_prefix ]] &&
                    [[ ${source:position} =~ $delimiter ]] && [[ ! ${credential_functions[${word:-:}]:-} ]]; then
                case $word in
                    false) truth=0 ;;
                    true|:) truth=1 ;;
                    *) return 0 ;;
                esac
                if (( truth != if_negated[-1] )); then
                    if_conditions[-1]=waiting-true
                else
                    if_conditions[-1]=waiting-false
                fi
            fi
            ;;
        waiting-false|waiting-true|waiting-sentinel)
            if (( ! command_position )) || [[ $word != "${condition_end[-1]}" ]]; then
                if_conditions[-1]=waiting-unknown
            fi
            ;;
    esac
}

credential_case_literal() {
    (( ! word_expansion )) &&
        ! resolves_credential_tilde "$word" 0
}

credential_case_matches() (
    if (( nocasematch )); then shopt -s nocasematch; fi
    [[ $1 == $2 ]]
)

invalidate_credential_if_condition() {
    if (( ${#if_conditions[@]} )) &&
            [[ ${if_conditions[-1]} == waiting-@(command|false|true|sentinel) ]]; then
        if_conditions[-1]=waiting-unknown
    fi
}

set_credential_if_dead() {
    dead_branches=$((dead_branches - if_dead[-1] + $1))
    if_dead[-1]=$1
}

leave_credential_case() {
    if [[ ${case_stack[case_index]} == body ]] && (( ! case_possible[case_index] )); then
        dead_branches=$((dead_branches - 1))
    fi
    unset 'case_stack[case_index]' 'case_subject[case_index]' 'case_known[case_index]' \
        'case_possible[case_index]' 'case_certain[case_index]' 'case_finished[case_index]' 'case_prior_possible[case_index]'
}

credential_list_word_is_empty() {
    local name fact bounds first count expansion separator
    local -a elements=() fields=()
    local quoted_array='^"\$\{([A-Za-z_][A-Za-z0-9_]*)\[@\]\}"$'
    local bare_array='^\$\{([A-Za-z_][A-Za-z0-9_]*)\[([@*])\]\}$'
    local bare_scalar='^\$([A-Za-z_][A-Za-z0-9_]*|\{[A-Za-z_][A-Za-z0-9_]*\})$'
    case $word in
        '"$@"'|'"${@}"') (( positional_empty )); return ;;
        '$@'|'$*'|'${@}'|'${*}')
            if (( positional_empty )); then return 0; fi
            [[ -n $positional_bounds ]] || return 1
            fact=array:$positional_bounds
            expansion=$word
            expansion=${expansion%\}}
            expansion=${expansion: -1}
            ;;
        *)
            if [[ $word =~ $quoted_array ]]; then
                name=${BASH_REMATCH[1]}
                fact=${variable_facts[$name]:-}
                [[ $fact == array:* ]] || return 1
                bounds=${fact#array:}
                [[ ${bounds%:*} == "${bounds#*:}" ]]
                return
            elif [[ $word =~ $bare_array ]]; then
                name=${BASH_REMATCH[1]}
                expansion=${BASH_REMATCH[2]}
                fact=${variable_facts[$name]:-}
            elif [[ $word =~ $bare_scalar ]]; then
                name=${BASH_REMATCH[1]}
                name=${name#\{}
                name=${name%\}}
                fact=${variable_facts[$name]:-}
                expansion=scalar
            elif (( nullglob && ! noglob && ! word_expansion && word_glob )); then
                credential_fields_vanish "$case_pattern"
                return
            else
                return 1
            fi
            ;;
    esac
    case $fact in
        unset)
            [[ $expansion == scalar ]]
            return
            ;;
        array:*)
            bounds=${fact#array:}
            first=${bounds%:*}
            count=$(( ${bounds#*:} - first ))
            if (( ! count )); then return 0; fi
            if [[ $expansion == scalar ]]; then
                if [[ ${sparse_array_bounds[$bounds]+present} ]]; then return 0; fi
                count=1
            fi
            elements=("${known_array_words[@]:first:count}")
            ;;
        scalar:*)
            [[ $expansion == scalar ]] || return 1
            if [[ $fact == scalar: ]]; then return 0; fi
            elements=("${fact#scalar:}")
            ;;
        *) return 1 ;;
    esac
    separator=${variable_facts[IFS]-scalar:$' \t\n'}
    [[ $separator == scalar:* ]] || return 1
    mapfile -d '' -t fields < <(split_credential_fields "${separator#scalar:}" "$expansion" "${elements[@]}")
    if (( ! nullglob || noglob )); then
        (( ! ${#fields[@]} ))
        return
    fi
    credential_fields_vanish "${fields[@]}"
}

credential_fields_vanish() (
    local IFS='' field
    local -a matches
    (( $# )) || return 0
    [[ -n $credential_directory ]] || return 1
    cd -- "$credential_directory" || return 1
    unset GLOBIGNORE
    set +f
    shopt -u failglob dotglob nocaseglob
    shopt -s nullglob
    if (( dotglob )); then shopt -s dotglob; fi
    for field; do
        [[ -n $field ]] || return 1
        matches=( $field )
        if (( ${#matches[@]} )); then return 1; fi
    done
    return 0
)

credential_cd_may_succeed() {
    local position=$position literal word_expansion word_glob word_constructed
    local options=1 home_fact flags
    while :; do
        while [[ ${source:position:1} == [[:blank:]] ]]; do position=$((position + 1)); done
        if skip_credential_redirection; then continue; fi
        case ${source:position:1} in
            ''|'#'|$'\n'|[\;\&\|\(\)])
                home_fact=${variable_facts[HOME]:-}
                if [[ -z $home_fact ]]; then cd_status=1; return 1; fi
                case $home_fact in
                    scalar:*) literal=${home_fact#scalar:} ;;
                    *) return 0 ;;
                esac
                break
                ;;
        esac
        literal='' word_expansion=0 word_glob=0 word_constructed=0
        read_shell_word '' 0 0 0 0 "$(( ! noglob ))" "$braceexpand"
        if (( word_expansion || word_glob || word_constructed )); then return 0; fi
        if (( options )); then
            case $literal in
                --) options=0; continue ;;
                -[LPe@]*)
                    flags=${literal//[^LP]/}
                    if [[ -n $flags ]]; then cd_mode=-${flags: -1}; fi
                    continue
                    ;;
            esac
        fi
        if [[ $literal == - ]]; then
            if [[ -z ${variable_facts[OLDPWD]:-} ]]; then cd_status=1; return 1; fi
            return 0
        fi
        break
    done
    # An empty destination succeeds without changing cwd. This helper's
    # return value controls cwd effects, separately from the command status.
    if [[ -z $literal ]]; then cd_status=0; return 1; fi
    if [[ $literal != /* && -z $credential_directory ]]; then return 0; fi
    cd_destination=$literal
    if [[ $literal != /* && $literal != . && $literal != .. && $literal != ./* && $literal != ../* ]]; then
        local cdpath_fact=${variable_facts[CDPATH]-unset} cdpath_index
        for cdpath_index in "${!pending_names[@]}"; do
            if [[ ${pending_names[cdpath_index]} == CDPATH ]]; then cdpath_fact=${pending_facts[cdpath_index]}; fi
        done
        case $cdpath_fact in
            unset|scalar:) ;;
            *) cd_destination=''; return 0 ;;
        esac
    fi
    if cd_directory=$(resolve_credential_directory "$literal" "$cd_mode"); then
        cd_status=0
        return 0
    fi
    local destination created_directory
    destination=$(credential_file_path "$literal") || return 0
    # Only a missing path inside the scanned tree is provably absent. A
    # modeled mkdir may create it; its effects also survive subshells.
    if [[ $destination == "$scan_root/"* && ! -e $destination && ! -L $destination ]]; then
        while IFS= read -r created_directory; do
            [[ -n $created_directory ]] || continue
            if [[ $created_directory == '*' || $destination == "$created_directory" ||
                    $destination == "$created_directory/"* || $created_directory == "$destination/"* ]]; then
                # It may succeed, so retaining the old cwd would make later
                # relative paths falsely appear absent in the original cwd.
                cd_destination=''
                return 0
            fi
        done <<< "$credential_created_directories"
        builtin_failed=1
        return 1
    fi
    return 0
}

append_credential_for_items() {
    local scope=$1 item
    shift
    for item; do
        loop_list_count[$scope]=$(( ${loop_list_count[$scope]:-0} + 1 ))
        loop_list_items[$scope:${loop_list_count[$scope]}]=scalar:$item
    done
}

# An entire quoted [@] pattern expansion contributes one operand per element.
read_credential_pattern_list_word() {
    local pattern_list_ref='^"\$\{(@|[A-Za-z_][A-Za-z0-9_]*\[@\])[#%/]'
    [[ $word =~ $pattern_list_ref && $word == *'}"' ]] || return 1
    local source=${word:1:-1} position=2 parameter_value=unknown pattern_list_known=0
    read_parameter_word 0 0 0 1 &&
        (( pattern_list_known && position == ${#source} ))
}

collect_credential_for_word() {
    local scope=$1 bounds='' fact
    local quoted_array='^"\$\{([A-Za-z_][A-Za-z0-9_]*)\[@\]\}"$'
    if [[ $word == @('"$@"'|'"${@}"'|'"${@'[\#%/]*'}"') ]]; then loop_uses_positionals[$scope]=1; fi
    local -a pattern_list_words=()
    if read_credential_pattern_list_word; then
        if (( ${#pattern_list_words[@]} )); then
            if_conditions[-1]=loop-body
            if (( ${loop_list_count[$scope]:-0} >= 0 )); then
                append_credential_for_items "$scope" "${pattern_list_words[@]}"
            fi
        fi
        return 0
    fi
    if credential_list_word_is_empty; then return 0; fi
    if_conditions[-1]=loop-body
    if (( ${loop_list_count[$scope]:-0} < 0 )); then return 0; fi
    if (( ! word_expansion && ! word_glob && ! word_constructed )); then
        append_credential_for_items "$scope" "$literal"
        return
    fi
    case $word in
        '"$@"'|'"${@}"') bounds=$positional_bounds ;;
        *)
            if [[ $word =~ $quoted_array ]]; then
                fact=${variable_facts[${BASH_REMATCH[1]}]:-}
                if [[ $fact == array:* ]]; then bounds=${fact#array:}; fi
            fi
            ;;
    esac
    if [[ -n $bounds ]]; then
        local first=${bounds%:*}
        append_credential_for_items "$scope" "${known_array_words[@]:first:${bounds#*:}-first}"
    else
        loop_list_count[$scope]=-1
    fi
}

# Validate invocation status independently of each valid operand's writes.
# This lookahead reads syntax only; normal scanning still visits expansions.
validate_credential_declaration() {
    local position=$position start literal='' word_expansion=0 word_glob=0 word_constructed=0 name
    local skip_expansion_effects=1 scan_word_substitutions=0
    local -a env_expansions=() env_expansion_quotes=()
    while (( position < ${#source} )); do
        while [[ ${source:position:1} == [[:blank:]] ]]; do position=$((position + 1)); done
        [[ ${source:position:1} != [';&|<>()'$'\n'] && -n ${source:position} ]] || break
        start=$position
        literal='' word_expansion=0 word_glob=0 word_constructed=0
        read_shell_word '' 0 2
        (( position > start )) || break
        name=${literal%%=*}
        name=${name%+}
        if (( ! word_expansion )) && [[ $literal != [-+]* && ! $name =~ $assignment_target$ ]]; then
            declaration_invalid=1
        fi
        if [[ ${source:position:1} == '(' && $literal == *= ]]; then
            position=$((position + 1))
            read_shell_word ')' 0 0 0 1
        fi
    done
}

# Read one printf operand without running expansions. Missing operands use
# printf's empty-string/zero default; unresolved operands stay conservative.
credential_printf_argument() {
    # Every operand expands before printf starts writing through %n. Read
    # operands against that snapshot, even after an earlier directive writes.
    if (( ${printf_snapshot:-0} )); then
        local input_name
        local -A variable_facts=()
        for input_name in "${!printf_input_facts[@]}"; do
            variable_facts[$input_name]=${printf_input_facts[$input_name]}
        done
    fi
    argument_known=1
    literal='' word_expansion=0 word_glob=0 word_constructed=0
    if read_printf_argument 2; then
        if (( word_expansion )) && ! resolve_env_word 1; then argument_known=0; fi
        if (( word_glob || word_constructed )); then argument_known=0; fi
    fi
}

credential_printf_invalid_number() {
    local value=$1 conversion=$2
    # The expanded word is a number literal, never an arithmetic expression
    # or variable name. Fixed directives validate Bash's numeric syntax without
    # executing operand text or allocating operand-controlled field widths.
    ! builtin printf "%$conversion" "$value" >/dev/null 2>&1
}

# Parse the format without executing it or allocating its requested widths.
# Bash extensions (including quoting, %n and time formats) remain valid.
credential_printf_invalid_format() {
    local position=$position start literal='' word_expansion=0 word_glob=0 word_constructed=0
    local skip_expansion_effects=1 scan_word_substitutions=0
    local -a env_expansions=() env_expansion_quotes=()
    local format conversion matched width precision argument_known invalid_number=0 value
    local name_pattern='^[A-Za-z_][A-Za-z0-9_]*$'
    local original_format pass_start next_position count_known=1 count_format='' counted name index fact
    local -a count_arguments=()
    local directive="^%([-+ #0']*([0-9]+|\*)?(\.([0-9]*|\*))?[hlL]*([bcdieEfgGosuxXaAFqQn%]|\([^)]*\)T))"
    read_printf_argument 2 || return 1
    case $literal in
        -v) read_printf_argument 2 || return 1; read_printf_argument 2 || return 1 ;;
        -v?*|--) read_printf_argument 2 || return 1 ;;
    esac
    if (( word_expansion )); then resolve_env_word 1 || return 1; fi
    (( ! word_glob && ! word_constructed )) || return 1
    original_format=$literal
    local printf_snapshot=0 input_name
    local -A printf_input_facts=()
    if [[ $original_format == *n* ]]; then
        printf_snapshot=1
        for input_name in "${!variable_facts[@]}"; do
            printf_input_facts[$input_name]=${variable_facts[$input_name]}
        done
    fi
    while :; do
        format=$original_format
        pass_start=$position
        while [[ -n $format ]]; do
            case $format in
                \\?*) count_format+=${format:0:2}; format=${format:2} ;;
                %*)
                    [[ $format =~ $directive ]] || return 0
                    matched=${BASH_REMATCH[0]} width=${BASH_REMATCH[2]} precision=${BASH_REMATCH[4]}
                    conversion=${matched: -1}
                    format=${format:${#matched}}
                    # Bound rendering just as the output journal does. Unsupported
                    # directives retain validity but make subsequent counts unknown.
                    if [[ $conversion != n ]]; then
                        count_format+=$matched
                        if [[ ! $matched =~ ^%[-+\ #0]*([0-9]{0,3}|\*)?([.]([0-9]{0,3}|\*))?[diouxXfFeEgGaAcbsq%]$ ]]; then count_known=0; fi
                    fi
                    for value in "$width" "$precision"; do
                        if [[ $value == '*' ]]; then
                            credential_printf_argument
                            if (( argument_known )) && credential_printf_invalid_number "$literal" d; then invalid_number=1; fi
                            if (( ! argument_known )) || [[ ! $literal =~ ^-?[0-9]{1,3}$ ]]; then count_known=0; fi
                            if [[ $conversion != n ]]; then count_arguments+=("$literal"); fi
                        fi
                    done
                    [[ $conversion != % ]] || continue
                    credential_printf_argument
                    if [[ $conversion == n ]]; then
                        if (( argument_known )) && [[ $literal =~ $name_pattern ]]; then
                            name=$literal index='' fact=unknown
                            if (( count_known )); then
                                # NUL output cannot be represented by Bash facts.
                                # read preserves trailing newlines and detects NUL.
                                if ! IFS= read -r -d '' counted < <(builtin printf -- "$count_format" "${count_arguments[@]}" 2>/dev/null || :); then
                                    fact=scalar:${#counted}
                                else count_known=0; fi
                            fi
                            if resolve_credential_nameref; then
                                if [[ ${readonly_facts[$name]:-0} == 1 ]]; then printf_readonly=1; return 1; fi
                                if [[ -n $index || ${variable_facts[$name]:-} == @(array:*|partial-array:*|assoc-array:*) ]]; then
                                    assign_credential_element "$name" "${index:-[0]}" "$fact"
                                else
                                    local value=$fact
                                    normalize_credential_assignment_fact "$name" ''
                                    change_credential_state "fact:$name" "$value"
                                fi
                            fi
                        fi
                        continue
                    fi
                    count_arguments+=("$literal")
                    if (( ! argument_known )); then count_known=0; fi
                    if [[ $conversion == b ]]; then
                        # Only an unescaped \c terminates output. It overrides
                        # even an earlier numeric error with success in Bash.
                        if (( ! argument_known )); then continue; fi
                        value=$literal
                        while [[ -n $value ]]; do
                            case $value in
                                '\c'*) printf_terminated=1; return 1 ;;
                                \\?*) value=${value:2} ;;
                                *) value=${value:1} ;;
                            esac
                        done
                    elif [[ $conversion == [dieEfgGouxXaAF] ]] && (( argument_known )) &&
                            credential_printf_invalid_number "$literal" "$conversion"; then
                        invalid_number=1
                    fi
                    ;;
                *) count_format+=${format:0:1}; format=${format:1} ;;
            esac
        done
        # Bash reuses the format while there are unconsumed operands.
        (( position > pass_start )) || break
        next_position=$position
        read_printf_argument 2 || break
        position=$next_position
    done
    (( invalid_number ))
}

credential_printf_readonly_target() {
    local position=$position start literal='' word_expansion=0 name index=''
    local skip_expansion_effects=1 scan_word_substitutions=0
    local -a env_expansions=() env_expansion_quotes=()
    read_printf_argument 2 || return 1
    case $literal in
        -v) read_printf_argument 2 || return 1; name=$literal ;;
        -v?*) name=${literal#-v} ;;
        *) return 1 ;;
    esac
    if (( word_expansion )); then
        resolve_env_word 1 || return 1
        name=$literal
    fi
    [[ $name =~ $assignment_target$ ]] || return 1
    index=${BASH_REMATCH[2]} name=${BASH_REMATCH[1]}
    resolve_credential_nameref && [[ ${readonly_facts[$name]:-0} == 1 ]]
}

scan_credential_statement() {
    local source=$1 position=${2:-0} start word literal='' word_expansion=0 word_glob=0 word_constructed=0
    local declaration_invalid=0 declaration_no_writes=0 printf_readonly=0 printf_terminated=0 command_redirection_unknown=0 command_redirection_failed=0 suppress_command_writes=0
    # Redirections precede the builtin's effects, including set -C itself.
    local command_noclobber=$noclobber
    local arithmetic_expansion_failed=0
    local builtin_failed=0 mkdir_arguments=0 mkdir_options=1
    local arithmetic_assignment_result='' arithmetic_assignment_failed=0 arithmetic_assignment_fatal=0
    local command_position=1 declaration=0 conditional=0 for_header=0 let_arguments=0 nameref_declaration=0
    local command_prefix='' prefix_options=0 prefix_operand=0 command_query=0 prefix_split=''
    local env_null=0 env_rejected=0 env_operands_started=0
    local -a split_words=() env_expansions=() env_expansion_quotes=() env_assignments=()
    local -A substitution_values=() substitution_statuses=()
    local assignment_substitution_status=0
    local split_index=0 split_start=0 from_split
    local select_header=0
    local option_expansion
    local detached_command=0 assignment_value='' local_options=0 local_builtin=0
    local pending_return=0 conditional_chain=0 pending_loop_action='' pending_loop_level=''
    local getopts_builtin=0 getopts_known=1
    local -a getopts_words=()
    local shift_builtin=0 shift_count=1 shift_known=1 shift_operands=0 shift_options=1
    local pending_function='' set_positionals=0 lastpipe_setting='' monitor_setting='' oldpwd_effect=''
    local cd_destination='' cd_directory='' cd_mode=-L cd_status=2 dotglob_setting=''
    local declaration_attributes=''
    local readonly_declaration=0 integer_declaration=0 function_declaration=0
    local unset_arguments=0 unset_home=0 set_options=0 shopt_options=0 local_declaration=0 associative_declaration=0
    local map_builtin=0 map_unknown=0 map_operand='' map_target=MAPFILE
    local -a map_options=()
    local read_builtin=0 read_options=1 read_operand='' read_array='' read_raw=0
    local read_limit='' read_poll=0 read_other_fd=0 read_delimiter='' read_custom_delimiter=0
    local read_flag read_index read_flags command_input='' output_command='' read_descriptor=0 compound_stdout=''
    local -a output_words=()
    local -A command_outputs=() command_fds=()
    local -a read_names=()
    local shopt_names=0 positional_words_known=1
    local -a positional_words=()
    local loop_reference='\$([A-Za-z_][A-Za-z0-9_]*|[0-9#]|\{([A-Za-z_][A-Za-z0-9_]*|[0-9]+|#)\})'
    local loop_word='[^[:space:];&|<>(){}$"'"'"'`\\*?\[]+'
    local loop_double='[^"$`\\]*'
    local loop_arithmetic='\$\(\([^;`]*\)\)'
    local loop_level="($loop_word|\"($loop_double|$loop_reference|$loop_arithmetic)*\"|'[^']*'|$loop_reference|$loop_arithmetic)+"
    local function_signature='^[[:blank:]]*\([[:blank:]]*\)'
    local function_name='^[A-Za-z_][A-Za-z0-9_.-]*$'
    local fact array_fact check_array was_case_pattern capture_case_pattern case_pattern
    local -a pending_names=() pending_facts=() pending_values=() pending_indices=() arithmetic_commands=() call_words=()
    local call_words_known=1
    local let_target='^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)'
    local char case_index assignment_name assignment_index rhs cursor allowed=0 reported=0
    local inside_substitution=${3:-0} scan_word_substitutions=1 skip_expansion_effects=0
    local assignment="$assignment_target"'\+?='
    local read_target="$assignment_target"'$'
    local env_assignment='^([A-Za-z_][A-Za-z0-9_]*)='
    local quoted_env_name="^([\"'])([A-Za-z_][A-Za-z0-9_]*)[\"']="
    local quoted_env_assignment="^([\"'])([A-Za-z_][A-Za-z0-9_]*)="
    local integer_rhs='^[+-]?[0-9]+$'
    local identifier='^[A-Za-z_][A-Za-z0-9_]*$'
    # Also accept a standalone case-arm fragment, as used in shell snippets.
    local case_fragment='^([[:blank:]]*(\([^)]*\)|[^[:space:];&|<>()]+\)))[[:blank:]]+[^[:space:];&|<>()][^;&|<>()]*;;'
    if (( ! inside_substitution )) && [[ ${case_stack[-1]} == outside && $source =~ $case_fragment ]]; then
        position=${#BASH_REMATCH[1]}
    fi
    if credential_line_allowed "$source"; then allowed=1; fi
    while (( position < ${#source} || split_index < ${#split_words[@]} )); do
        from_split=0
        case_index=$(( ${#case_stack[@]} - 1 ))
        if (( split_index < ${#split_words[@]} )); then
            from_split=1
            start=$split_start
            literal=${split_words[split_index]}
            word=$literal
            word_expansion=0 word_glob=0 word_constructed=0
            if [[ $literal == *"$env_expansion_marker"* ]]; then word_expansion=1; fi
            split_index=$((split_index + 1))
        else
            char=${source:position:1}
            case $char in
                $'\n')
                    if (( ! conditional )); then
                        if (( ! pipeline_next || ! command_position )); then
                            finish_credential_command
                            conditional_chain=0
                            if [[ ${source:position:2} == @('&&'|'||') ]]; then conditional_chain=1; fi
                            pipeline_next=0
                        fi
                        detached_command=0
                        command_position=1
                        declaration=0
                        local_declaration=0
                        shopt_options=0
                        shopt_names=0
                        for_header=0
                        let_arguments=0
                        command_prefix=''
                        unset_arguments=0
                        set_options=0
                        prefix_options=0
                        select_header=0
                        prefix_operand=0
                        command_query=0
                        reported=0
                    fi
                    position=$((position + 1))
                    continue
                    ;;
                [[:space:]]) position=$((position + 1)); continue ;;
                ';'|'&'|'|'|'('|')')
                    if (( ! conditional )); then
                        if [[ ${source:position:2} == '&>' ]]; then
                            invalidate_credential_if_condition
                            skip_credential_redirection 1
                            continue
                        fi
                        if [[ ${source:position:2} == '((' &&
                                ${case_stack[case_index]} != pattern ]]; then
                            start=$((position + 2))
                            position=$((position + 1))
                            # Locate the closing delimiter before scanning the expression's effects.
                            scan_word_substitutions=0 read_shell_word ')' 0 0 0 1
                            if (( (command_position || for_header) && ! allowed )); then
                                scan_credential_arithmetic "$start" "$((position - 2))" 1
                            fi
                            if (( for_header )); then
                                local header=${source:start:position-start-2} loop_scope=${isolated_scopes[-1]}
                                if [[ $header == *';'*';'* ]]; then
                                    local init=${header%%;*} rest=${header#*;} condition
                                    condition=${rest%%;*}
                                    if [[ $condition =~ ^[[:space:]]*$ ]]; then condition=1; fi
                                    loop_arithmetic_conditions[$loop_scope]=$condition
                                    loop_arithmetic_steps[$loop_scope]=${rest#*;}
                                    if_conditions[-1]=loop-body
                                    if (( ! dead_branches )); then
                                        apply_credential_arithmetic_assignment "$init"
                                        if (( ! arithmetic_assignment_failed )); then
                                            apply_credential_arithmetic_assignment "$condition"
                                        fi
                                        if (( arithmetic_assignment_failed )); then
                                            if_conditions[-1]=for-empty
                                        elif [[ -n $arithmetic_assignment_result ]]; then
                                            if (( arithmetic_assignment_result )); then if_conditions[-1]=for-arithmetic
                                            else if_conditions[-1]=for-empty; fi
                                        fi
                                    fi
                                fi
                            fi
                            if (( command_position && ! for_header )); then
                                local expression=${source:start:position-start-2} arithmetic_result arithmetic_status=0
                                arithmetic_commands+=("$expression")
                                arithmetic_result=$(credential_arithmetic_value "$expression") || arithmetic_status=$?
                                if (( arithmetic_status == 2 )); then arithmetic_result=0; fi
                                if (( ${#if_conditions[@]} && arithmetic_status != 1 )) &&
                                        [[ ${condition_kind[-1]} == if && ${if_conditions[-1]} == waiting-command ]]; then
                                    if (( (arithmetic_result != 0) != if_negated[-1] )); then
                                        if_conditions[-1]=waiting-true
                                    else
                                        if_conditions[-1]=waiting-false
                                    fi
                                else
                                    invalidate_credential_if_condition
                                fi
                            fi
                            command_position=0
                            for_header=0
                            continue
                        fi
                        if [[ $char != ';' ]]; then invalidate_credential_if_condition; fi
                        if [[ ${case_stack[case_index]} != pattern ]]; then
                            case ${source:position:2} in
                                '||'|'&&') ;;
                                '|'*) detached_command=2 ;;
                                '&'*) detached_command=1 ;;
                            esac
                            finish_credential_command
                            char=${source:position:1}
                            pipeline_next=0
                            conditional_chain=0
                            case ${source:position:2} in
                                '||'|'&&') conditional_chain=1 ;;
                                ';;'|';&') ;;
                                '|&') pipeline_next=1; position=$((position + 1)) ;;
                                '|'*) pipeline_next=1 ;;
                            esac
                            detached_command=0
                        fi
                        was_case_pattern=0
                        if [[ ${case_stack[case_index]} == pattern ]]; then
                            was_case_pattern=1
                            if [[ $char == ')' ]]; then
                                case_stack[case_index]=body
                                if (( ! case_possible[case_index] )); then
                                    dead_branches=$((dead_branches + 1))
                                fi
                            elif [[ $char == '(' && ${source:position-1:1} == [@+*?!] ]]; then
                                if (( ! case_finished[case_index] )); then case_possible[case_index]=1; fi
                                position=$((position + 1))
                                read_shell_word ')'
                                continue
                            fi
                        elif [[ ${case_stack[case_index]} == body ]]; then
                            case ${source:position:3} in
                                ';;&'*|';;'*|';&'*)
                                    clear_credential_abort "${isolated_scopes[-1]}"
                                    if (( ! case_possible[case_index] )); then
                                        dead_branches=$((dead_branches - 1))
                                    fi
                                    case ${source:position:3} in
                                        ';;&'*)
                                            case_certain[case_index]=0
                                            case_possible[case_index]=0
                                            position=$((position + 1))
                                            ;;
                                        ';;'*)
                                            if (( case_certain[case_index] )); then
                                                case_finished[case_index]=1
                                            elif (( case_possible[case_index] )); then
                                                case_prior_possible[case_index]=1
                                            fi
                                            case_certain[case_index]=0
                                            case_possible[case_index]=0
                                            ;;
                                    esac
                                    case_stack[case_index]=pattern
                                    position=$((position + 1))
                                    ;;
                            esac
                        fi
                        if (( ! was_case_pattern )) && [[ ${case_stack[case_index]} != pattern ]]; then
                            case ${source:position:2} in
                                '||'|'&&') position=$((position + 1)) ;;
                            esac
                            if [[ $char == '(' ]] && (( command_position )); then
                                if [[ -n $function_body_pending ]]; then
                                    enter_credential_scope function-paren "$position"
                                    function_body_pending=''
                                else
                                    enter_credential_scope paren "$position"
                                fi
                            elif [[ $char == ')' && $was_case_pattern == 0 ]]; then
                                position=$((position + 1))
                                leave_credential_scope paren
                                position=$((position - 1))
                            fi
                        fi
                        command_position=1
                        if [[ $char == ')' && $was_case_pattern == 0 ]]; then command_position=0; fi
                        declaration=0
                        local_declaration=0
                        shopt_options=0
                        shopt_names=0
                        for_header=0
                        let_arguments=0
                        command_prefix=''
                        unset_arguments=0
                        set_options=0
                        prefix_options=0
                        select_header=0
                        prefix_operand=0
                        command_query=0
                    fi
                    position=$((position + 1))
                    continue
                    ;;
                '<'|'>')
                    if [[ -z ${substitution_ends[position]:-} ]]; then
                        if (( ! conditional )); then
                            invalidate_credential_if_condition
                            skip_credential_redirection 1
                        else
                            position=$((position + 1))
                        fi
                        continue
                    fi
                    ;;
                [0-9]|'{')
                    if (( (command_position || set_options || set_positionals || read_builtin || ${#output_command} || ${#pending_function}) && ! conditional )) &&
                        [[ ${case_stack[case_index]} != pattern ]] && skip_credential_redirection 1; then
                        invalidate_credential_if_condition
                        continue
                    fi
                    ;;
            esac
            start=$position
            literal=''
            word_expansion=0 word_glob=0 word_constructed=0
            capture_case_pattern=0
            case_pattern=''
            if [[ ${case_stack[case_index]} == pattern ]]; then capture_case_pattern=1; fi
            # A certain match skips remaining alternatives and ;& patterns.
            if (( capture_case_pattern && case_known[case_index] &&
                    (case_finished[case_index] || case_certain[case_index]) )); then
                skip_expansion_effects=1 read_shell_word '' 0 2 0 1 0 0 1
            elif (( set_options || set_positionals || shift_builtin )); then
                read_shell_word '' 0 2 0 1 "$(( ! noglob ))" "$braceexpand" "$capture_case_pattern" 2
            elif (( for_header == 3 || select_header == 3 || ${#output_command} )); then
                read_shell_word '' 0 2 0 1 "$(( ! noglob ))" "$braceexpand" 1 2
            else
                read_shell_word '' 0 2 0 1 0 0 "$capture_case_pattern"
            fi
            word=${source:start:position-start}
        fi

        case ${case_stack[case_index]} in
            subject)
                if credential_case_literal ||
                    { (( word_expansion )) &&
                      ! resolves_credential_tilde "$word" 0 && resolve_env_word 1 1; }; then
                    case_subject[case_index]=$literal
                    case_known[case_index]=1
                fi
                if (( ! case_known[case_index] )); then scope_stdout[${isolated_scopes[-1]}]=unknown; fi
                case_stack[case_index]=header
                continue
                ;;
            header)
                if [[ $word == in ]]; then case_stack[case_index]=pattern; fi
                continue
                ;;
            pattern)
                if [[ $word == esac ]]; then
                    leave_credential_case
                    leave_credential_scope case
                    command_position=0
                elif (( ! case_finished[case_index] )); then
                    if [[ $word == [@+*?!] && ${source:position:1} == '(' ]]; then continue; fi
                    if (( word_expansion )) && resolve_credential_glob_pattern; then
                        word_expansion=0
                    fi
                    if (( ! case_known[case_index] )) || ! credential_case_literal; then
                        case_possible[case_index]=1
                        scope_stdout[${isolated_scopes[-1]}]=unknown
                    elif credential_case_matches "${case_subject[case_index]}" "$case_pattern"; then
                        case_possible[case_index]=1
                        case_certain[case_index]=1
                    fi
                fi
                continue
                ;;
        esac
        if (( ${#if_conditions[@]} )); then
            local comparison_end comparison_status
            local condition_read_connector condition_read_start
            if (( command_position )) && [[ -z $command_prefix &&
                    ${condition_kind[-1]} == @(while|until) && ${if_conditions[-1]} == waiting-command &&
                    $word == @('[['|'['|test) ]] && find_credential_condition_read "$start"; then
                local loop_scope=${isolated_scopes[-1]}
                loop_prefix_tests[$loop_scope]=${source:start:condition_read_connector-start}
                loop_prefix_operator[$loop_scope]=${source:condition_read_connector:2}
                position=$condition_read_start
                continue
            fi
            if (( command_position )) && [[ -z $command_prefix &&
                    ${condition_kind[-1]} == if && ${if_conditions[-1]} == waiting-command &&
                    $word == @('[['|'['|test) ]] &&
                    parse_credential_comparison "$start"; then
                case $comparison_status in
                    0|1)
                        if (( comparison_status == if_negated[-1] )); then
                            if_conditions[-1]=waiting-true
                        else
                            if_conditions[-1]=waiting-false
                        fi
                        ;;
                    *) if_conditions[-1]=waiting-unknown ;;
                esac
                position=$comparison_end
                output_command=test
                command_position=0
                continue
            fi
            if (( command_position && ! if_negated[-1] )) && [[ -z $command_prefix &&
                    ${condition_kind[-1]} == @(while|until) && ${if_conditions[-1]} == waiting-command &&
                    $word == @('[['|'['|test) ]] && parse_credential_comparison "$start"; then
                local loop_status loop_condition_end loop_scope=${isolated_scopes[-1]}
                if evaluate_credential_loop_condition "${source:comparison_end}" "$comparison_status"; then
                    loop_comparisons[$loop_scope]=${source:start:comparison_end+loop_condition_end-start}
                    loop_until[$loop_scope]=0
                    if [[ ${condition_kind[-1]} == until ]]; then loop_until[$loop_scope]=1; fi
                    case $loop_status in
                        0) if_conditions[-1]=waiting-true ;;
                        1) if_conditions[-1]=waiting-false ;;
                        *) if_conditions[-1]=waiting-unknown ;;
                    esac
                    position=$((comparison_end + loop_condition_end))
                    command_position=0
                    continue
                fi
            fi
            local guarded_control="^[[:space:]]*(&&|\|\|)[[:space:]]*(break|continue|return)([[:blank:]]+($loop_level))?[[:blank:]]*(;|$|"$'\n'")"
            if (( command_position && ! conditional_chain )) && [[ -z $command_prefix &&
                    $word == @('[['|'['|test) ]] &&
                    parse_credential_comparison "$start" &&
                    [[ ${source:comparison_end} =~ $guarded_control ]]; then
                local control_operator=${BASH_REMATCH[1]} control_word=${BASH_REMATCH[2]}
                local control_level=${BASH_REMATCH[4]}
                local control_start=$((comparison_end + ${#BASH_REMATCH[0]} - ${#BASH_REMATCH[-1]}))
                # Advance to the command boundary and schedule an admitted
                # return for the normal command finalizer.
                position=$control_start
                if [[ $comparison_status:$control_operator == @('0:&&'|'1:||') &&
                        ! ${credential_functions[$control_word]:-} ]]; then
                    if [[ $control_word == @(break|continue) ]]; then
                        pending_loop_action=$control_word
                        pending_loop_level=$control_level
                    elif (( function_depth )) && [[ -z $control_level ]]; then
                        while [[ ${source:control_start-1:1} == [[:blank:]] ]]; do control_start=$((control_start - 1)); done
                        pending_return=$((control_start - ${#control_word} + 1))
                    fi
                fi
                command_position=0
                continue
            fi
            observe_credential_if_word
        fi
        if (( conditional )); then
            if [[ $word == ']]' ]]; then conditional=0; fi
            continue
        fi
        case $for_header in
            1) loop_names[${isolated_scopes[-1]}]=$word; for_header=2; if_conditions[-1]=for-name; continue ;;
            2) for_header=0 ;;
            3)
                collect_credential_for_word "${isolated_scopes[-1]}"
                continue
                ;;
        esac
        case $select_header in
            1) loop_names[${isolated_scopes[-1]}]=$word; select_header=2; if_conditions[-1]=for-name; continue ;;
            2) select_header=0 ;;
            3)
                local loop_scope=${isolated_scopes[-1]} item_number
                local -a pattern_list_words=()
                local pattern_list_item
                if read_credential_pattern_list_word; then
                    for pattern_list_item in "${pattern_list_words[@]}"; do
                        if_conditions[-1]=loop-body
                        item_number=$(( ${select_item_counts[$loop_scope]:-0} + 1 ))
                        select_item_counts[$loop_scope]=$item_number
                        select_items[$loop_scope:$item_number]=scalar:$pattern_list_item
                    done
                elif ! credential_list_word_is_empty; then
                    if_conditions[-1]=loop-body
                    item_number=$(( ${select_item_counts[$loop_scope]:-0} + 1 ))
                    select_item_counts[$loop_scope]=$item_number
                    if (( word_expansion || word_glob || word_constructed )); then
                        unknown_select_lists[$loop_scope]=1
                    else
                        select_items[$loop_scope:$item_number]=scalar:$literal
                    fi
                fi
                if (( ! allowed )) && [[ ${loop_names[$loop_scope]} =~ $credential_keyword ]] &&
                    list_word_has_literal "$start" "$position"; then
                    report_credential_assignment "$start"
                fi
                continue
                ;;
        esac
        if [[ ${if_conditions[@]: -1} == for-name && $word == in ]]; then
            # Both loops track list emptiness, but select also assigns each item.
            if [[ ${condition_kind[-1]} == select ]]; then
                select_header=3
            else
                for_header=3
            fi
            if_conditions[-1]=for-empty
            continue
        fi
        if [[ $output_command == @(echo|printf|cat|rm|mkdir) ]]; then
            local output_value=$literal output_known=1 output_unquoted=0 output_index
            for output_index in "${!env_expansions[@]}"; do
                if [[ $literal == *"$env_expansion_marker$output_index$env_expansion_marker"* &&
                      ${env_expansion_quotes[output_index]} != '"' ]]; then output_unquoted=1; fi
            done
            if (( word_expansion )); then
                if resolve_env_word 1; then output_value=$literal; else output_known=0; fi
            fi
            if (( ! output_known || word_glob || word_constructed )); then
                if [[ $output_command == @(rm|mkdir) ]]; then change_credential_state file:uncertain 1; fi
                output_command=''
            elif (( output_unquoted )) &&
                    [[ ${variable_facts[IFS]-scalar:$' \t\n'} != "scalar:"$' \t\n' ||
                       $output_value == *[[:space:]*?\[]* ]]; then
                if [[ $output_command == @(rm|mkdir) ]]; then change_credential_state file:uncertain 1; fi
                output_command=''
            elif [[ -n $output_value || $word == '"'* || $word == "'"* ]] || (( ! word_expansion )); then
                output_words+=("$output_value")
            fi
        fi
        if [[ -n $pending_function ]]; then
            if (( word_expansion || word_glob || word_constructed )); then call_words_known=0; fi
            if (( call_words_known )); then call_words+=("$literal"); fi
            continue
        fi
        if (( getopts_builtin )); then
            if (( word_expansion )) && resolve_env_word 1; then word_expansion=0; fi
            if (( word_expansion || word_glob || word_constructed )); then getopts_known=0; fi
            getopts_words+=("$literal")
            continue
        fi
        if (( shift_builtin )); then
            local -a let_words=()
            local shift_word shift_reference='' shift_quote=''
            # Standalone unquoted scalar expansions can disappear or split into
            # several operands. Quoted arrays use the existing argv resolver.
            if [[ $literal =~ $env_expansion_ref && ${BASH_REMATCH[0]} == "$literal" ]]; then
                shift_reference=${env_expansions[BASH_REMATCH[1]]}
                shift_quote=${env_expansion_quotes[BASH_REMATCH[1]]:-}
            fi
            if [[ -n $shift_reference && $shift_quote != '"' ]] && resolve_env_word 1 0 1; then
                local separator=${variable_facts[IFS]-scalar:$' \t\n'}
                if [[ $separator == scalar:* ]]; then
                    mapfile -d '' -t let_words < <(
                        split_credential_fields "${separator#scalar:}" scalar "$literal"
                    )
                    if (( ! noglob )); then
                        mapfile -d '' -t let_words < <(expand_credential_let_globs "${let_words[@]}")
                        if (( ${#let_words[@]} )) && [[ ${let_words[-1]} == $'\2' ]]; then
                            unset 'let_words[-1]'
                        else
                            shift_known=0
                        fi
                    fi
                else
                    shift_known=0
                fi
            elif ! resolve_credential_let_words; then
                shift_known=0
            fi
            for shift_word in "${let_words[@]}"; do
                if (( shift_options )) && [[ $shift_word == -- ]]; then
                    shift_options=0
                    continue
                fi
                shift_options=0
                shift_operands=$((shift_operands + 1))
                if (( shift_operands == 1 )); then shift_count=$shift_word; fi
            done
            continue
        fi
        if (( let_arguments )); then
            local -a let_words=()
            if resolve_credential_let_words; then
                arithmetic_commands+=("${let_words[@]}")
            else
                arithmetic_commands+=('?')
            fi
            if (( ! allowed )) && [[ $literal =~ $let_target ]]; then
                assignment_name=${BASH_REMATCH[1]}
                word=${literal:${#BASH_REMATCH[0]}}
                if [[ $assignment_name =~ $credential_keyword && ${assignment_name,,} != *_count ]]; then
                    restore_marked_value "$word" 0
                    if arithmetic_assignment_has_literal "$rhs" '$'; then
                        report_credential_assignment "$start"
                    fi
                fi
            fi
            continue
        fi
        if (( map_builtin )); then
            if (( word_expansion )) && ! resolve_env_word 1; then map_unknown=1; fi
            if [[ -n $map_operand ]]; then
                if [[ $map_operand == -d || $literal =~ ^[0-9]{1,8}$ ]]; then
                    map_options+=("$map_operand" "$literal")
                else
                    map_unknown=1
                fi
                map_operand=''
            elif (( read_options )) && [[ $literal == -- ]]; then
                read_options=0
            elif (( read_options )) && [[ $literal == -t ]]; then
                map_options+=(-t)
            elif (( read_options )) && [[ $literal == @(-n|-s|-d) ]]; then
                map_operand=$literal
            elif (( read_options )) && [[ $literal == -?* ]]; then
                map_unknown=1
                # Unsupported option arguments must not be mistaken for targets.
                if [[ $literal == @(-O|-u|-C|-c) ]]; then map_operand=$literal; fi
            elif [[ $literal =~ $identifier ]]; then
                map_target=$literal
                read_options=0
            else
                map_unknown=1
            fi
            continue
        fi
        if (( read_builtin )); then
            if [[ -n $read_operand ]]; then
                if (( word_expansion )) && ! resolve_env_word 1; then literal='?'; fi
                set_credential_read_option "$read_operand" "$literal"
                read_operand=''
            elif (( read_options )) && [[ $literal == -- ]]; then
                read_options=0
            elif (( read_options )) && [[ $literal == -?* ]]; then
                read_flags=${literal:1}
                for (( read_index=0; read_index<${#read_flags}; read_index++ )); do
                    read_flag=${read_flags:read_index:1}
                    case $read_flag in
                        r) read_raw=1 ;;
                        a|d|i|n|N|p|t|u)
                            if (( read_index + 1 == ${#read_flags} )); then
                                read_operand=$read_flag
                            else
                                set_credential_read_option "$read_flag" "${read_flags:read_index+1}"
                            fi
                            break
                            ;;
                    esac
                done
            else
                read_options=0
                if { (( ! word_expansion )) || resolve_env_word 1; } &&
                        [[ $literal =~ $read_target ]]; then read_names+=("$literal"); fi
            fi
            continue
        fi
        if (( mkdir_arguments )); then
            if (( mkdir_options )) && [[ $literal == -- ]]; then
                mkdir_options=0
            elif (( mkdir_options )) && [[ $literal == -* ]]; then
                :
            elif (( ! dead_branches && ! suppress_command_writes )); then
                local created_directory=''
                if (( ! word_expansion && ! word_glob && ! word_constructed )); then
                    created_directory=$(credential_file_path "$literal") || created_directory=''
                fi
                if [[ -n $created_directory ]]; then
                    credential_created_directories+=$created_directory$'\n'
                else
                    credential_created_directories+=$'*\n'
                fi
            fi
            continue
        fi
        if (( unset_arguments )); then
            if (( ! word_expansion )); then
                case $literal in
                    -f) unset_arguments=2 ;;
                    -v) unset_arguments=1 ;;
                    -n) unset_arguments=3 ;;
                    *)
                        if (( unset_arguments == 2 )); then
                            if [[ ${readonly_facts[function:$literal]:-0} == 1 ]]; then
                                builtin_failed=1
                            elif [[ $literal =~ $function_name ]]; then
                                change_credential_state "function:$literal" ''
                            fi
                        elif [[ $literal =~ $assignment_target$ ]]; then
                            local name=${BASH_REMATCH[1]} index=${BASH_REMATCH[2]}
                            if (( unset_arguments == 3 )); then
                                [[ ${variable_facts[$name]:-} == nameref:* ]] || continue
                            else
                                resolve_credential_nameref || continue
                            fi
                            if [[ ${readonly_facts[$name]:-0} == 1 ]]; then
                                builtin_failed=1
                                continue
                            fi
                            if [[ -z $index ]]; then
                                change_credential_state "integer:$name" ''
                                change_credential_state "attributes:$name" ''
                            fi
                            if [[ -n $index ]]; then
                                assign_credential_element "$name" "$index" unset
                            elif [[ $name == OLDPWD ]]; then
                                oldpwd_effect=unset
                            elif [[ $name == HOME ]]; then
                                unset_home=1
                            elif [[ $name == IFS ]]; then
                                change_credential_state fact:IFS ''
                            else
                                change_credential_state "fact:$name" unset
                            fi
                        fi
                        ;;
                esac
            fi
            continue
        fi
        if (( shopt_options )); then
            # Track the -s, -u and -o flags independently until option names begin.
            if (( ! shopt_names )) && [[ $literal == -?* ]]; then
                if (( ! word_expansion )) && [[ $literal =~ ^-[osuqp]+$ ]]; then
                    if [[ $literal == *s* ]]; then shopt_options=$((shopt_options | 2)); fi
                    if [[ $literal == *u* ]]; then shopt_options=$((shopt_options | 4)); fi
                    if [[ $literal == *o* ]]; then shopt_options=$((shopt_options | 8)); fi
                elif [[ $literal == -- ]]; then
                    shopt_names=1
                else
                    shopt_options=1
                    shopt_names=1
                fi
            else
                shopt_names=1
                option_expansion=$((shopt_options & 6))
                if (( ! word_expansion && (option_expansion == 2 || option_expansion == 4) )); then
                    if (( shopt_options & 8 )); then
                        case $literal in
                            monitor) monitor_setting=$((option_expansion == 2)) ;;
                            noglob|braceexpand|noclobber) change_credential_state "$literal" "$((option_expansion == 2))" ;;
                        esac
                    else
                        case $literal in
                            lastpipe) lastpipe_setting=$((option_expansion == 2)) ;;
                            dotglob) dotglob_setting=$((option_expansion == 2)) ;;
                            patsub_replacement|nocasematch|nullglob)
                                change_credential_state "$literal" "$((option_expansion == 2))"
                                ;;
                        esac
                    fi
                fi
            fi
            continue
        fi
        if (( set_options || set_positionals )); then
            if (( set_positionals || set_options == 4 || (word_expansion && set_options == 1) )); then
                set_positionals=2
                set_options=0
            elif (( set_options > 1 )); then
                case $literal in
                    noglob|braceexpand|noclobber) change_credential_state "$literal" "$((set_options == 2))" ;;
                    monitor) monitor_setting=$((set_options == 2)) ;;
                esac
                set_options=1
            else
                case $literal in
                    --) set_positionals=1; set_options=0 ;;
                    -) set_options=4 ;;
                    [-+]*)
                        option_expansion=0
                        if [[ $literal == -* ]]; then option_expansion=1; fi
                        for (( cursor=1; cursor<${#literal}; cursor++ )); do
                            case ${literal:cursor:1} in
                                f) change_credential_state noglob "$option_expansion" ;;
                                B) change_credential_state braceexpand "$option_expansion" ;;
                                C) change_credential_state noclobber "$option_expansion" ;;
                                m) monitor_setting=$option_expansion ;;
                                o)
                                    set_options=3
                                    if [[ $literal == -* ]]; then set_options=2; fi
                                    ;;
                            esac
                        done
                        ;;
                    *) set_positionals=2; set_options=0 ;;
                esac
            fi
            if (( set_positionals == 2 )); then
                if (( word_expansion || word_glob || word_constructed )); then positional_words_known=0; fi
                if (( positional_words_known )); then positional_words+=("$literal"); fi
            fi
            continue
        fi
        if (( declaration && ! command_position )); then
            if (( declaration_no_writes )); then continue; fi
            local declaration_name=${literal%%=*}
            declaration_name=${declaration_name%+}
            if (( ! word_expansion )) && [[ $literal != [-+]* && ! $declaration_name =~ $assignment_target$ ]]; then
                declaration_invalid=1
                continue
            fi
        fi
        # Quoting a whole declaration operand does not hide its assignment.
        # Move the opening quote past the name, retaining RHS expansion rules.
        if (( declaration )) && [[ $word =~ $quoted_env_assignment ]]; then
            local declaration_quote=${BASH_REMATCH[1]} declaration_name=${BASH_REMATCH[2]}
            word=$declaration_name=$declaration_quote${word:${#BASH_REMATCH[0]}}
        fi
        if (( local_builtin )); then
            local_options=0
            if (( local_builtin == 1 && ! word_expansion )) && [[ $literal == - ]]; then local_options=1; fi
            local_builtin=2
        fi
        if (( command_position && word_expansion )) && [[ $command_prefix == env ]] &&
                splice_env_array; then
            continue
        fi
        if (( command_position && (prefix_operand || command_query) )); then
            if (( command_query )); then
                command_position=0
            else
                if (( prefix_operand == 3 && word_expansion )) && resolve_env_word 1; then
                    word_expansion=0
                fi
                consume_command_prefix "$literal" "$word_expansion"
                if [[ -n $prefix_split ]]; then
                    split_start=$start
                    split_env_words "$prefix_split" || :
                fi
                prefix_split=''
            fi
            continue
        fi
        if (( command_position )) && [[ $command_prefix == env ]]; then
            assignment_name=''
            if (( from_split )); then
                if [[ $literal =~ $env_assignment ]]; then
                    assignment_name=${BASH_REMATCH[1]}
                    restore_marked_value "${literal#*=}"
                fi
            elif [[ $word =~ $quoted_env_name &&
                    ${BASH_REMATCH[0]: -2:1} == "${BASH_REMATCH[1]}" ]]; then
                assignment_name=${BASH_REMATCH[2]}
                rhs=${word:${#BASH_REMATCH[0]}}
            elif [[ $word =~ $quoted_env_assignment ]]; then
                assignment_name=${BASH_REMATCH[2]}
                rhs=${BASH_REMATCH[1]}${word:${#BASH_REMATCH[0]}}
            elif [[ $word =~ $env_assignment ]]; then
                assignment_name=${BASH_REMATCH[1]}
                rhs=${word#*=}
            elif (( ! word_expansion )) && [[ $literal =~ $env_assignment ]]; then
                assignment_name=${BASH_REMATCH[1]}
                rhs=${literal#*=}
                if [[ $rhs == *'$'* ]]; then printf -v rhs '%q' "$rhs"; fi
            fi
        elif { (( declaration )) || { (( command_position )) &&
                [[ -z $command_prefix || $command_prefix == time ]]; }; } &&
                [[ $word =~ $assignment ]]; then
            assignment_name=${BASH_REMATCH[1]}
            assignment_index=${BASH_REMATCH[2]}
            rhs=${word:${#BASH_REMATCH[0]}}
            rhs=${rhs#=}
        else
            assignment_name=''
        fi
        if [[ -n $assignment_name ]]; then
            if [[ $command_prefix == env ]]; then
                prefix_options=0
                env_operands_started=1
            else
                fact=unknown
                assignment_value=''
                if [[ -z $rhs && ${source:position:1} == '(' ]]; then
                    position=$((position + 1))
                    check_array=0
                    if (( ! allowed )) && [[ $assignment_name =~ $credential_keyword ]]; then check_array=1; fi
                    scan_credential_array "$check_array"
                    fact=$array_fact
                elif [[ $word == "$assignment_name$assignment_index="* ]] && ! credential_assignment_has_tilde "$rhs"; then
                    if (( ! word_expansion )); then
                        fact=scalar:${literal#*=}
                    else
                        if (( function_depth )); then assignment_value=$rhs; fi
                        resolve_credential_assignment "$rhs"
                    fi
                fi
                if [[ $word == "$assignment_name$assignment_index+="* ]]; then
                    if [[ $fact != array:* ]]; then
                        if (( word_expansion )); then resolve_credential_assignment "$rhs"
                        else fact=scalar:${literal#*=}
                        fi
                        if [[ $fact == scalar:* ]]; then fact=append:${fact#scalar:}; fi
                    else
                        fact=unknown
                    fi
                fi
                if (( ! declaration )); then
                    local substitution_rhs=$rhs
                    if [[ $substitution_rhs == \"*\" ]]; then substitution_rhs=${substitution_rhs:1:-1}; fi
                    if [[ -n $substitution_rhs && ${substitution_statuses[$substitution_rhs]+present} ]]; then
                        assignment_substitution_status=${substitution_statuses[$substitution_rhs]}
                    elif [[ $rhs == *'$('* || $rhs == *'`'* ]]; then
                        assignment_substitution_status=2
                    fi
                fi
                if (( declaration )); then
                    apply_credential_attributes "$assignment_name" "$local_declaration"
                fi
                if (( local_declaration )) && [[ $(credential_fact_is_local "fact:$assignment_name") == 0 ]]; then
                    change_credential_state "integer:$assignment_name" 0 1
                fi
                if (( integer_declaration )); then
                    change_credential_state "integer:$assignment_name" "$((integer_declaration == 1))" "$local_declaration"
                fi
                if (( nameref_declaration )); then
                    if [[ $fact == scalar:* && ${fact#scalar:} =~ $assignment_target$ ]]; then
                        fact=nameref:${fact#scalar:}
                    else
                        fact=unknown
                    fi
                    change_credential_state "fact:$assignment_name" "$fact" "$local_declaration"
                elif (( associative_declaration )); then
                    if [[ $fact != assoc-array:* ]]; then fact=assoc-array:0:0; fi
                    change_credential_state "fact:$assignment_name" "$fact" "$local_declaration"
                elif (( declaration )); then
                    if (( readonly_declaration && ! local_declaration )); then
                        local name=$assignment_name index=$assignment_index
                        if resolve_credential_nameref; then
                            assignment_name=$name assignment_index=$index
                        fi
                    fi
                    if [[ -n $assignment_index ]]; then
                        assign_credential_element "$assignment_name" "$assignment_index" "$fact" "$local_declaration"
                    else
                        change_credential_state "fact:$assignment_name" "$fact" "$local_declaration" "$assignment_value"
                    fi
                else
                    pending_names+=("$assignment_name")
                    pending_facts+=("$fact")
                    pending_indices+=("$assignment_index")
                    pending_values+=("$assignment_value")
                fi
                if (( readonly_declaration )); then
                    mark_credential_readonly "$assignment_name" "$local_declaration" "$nameref_declaration"
                fi
                if [[ -z $rhs && $word == *= ]]; then continue; fi
            fi
            if (( ! allowed )) && { (( ! reported )) || [[ $command_prefix == env ]]; } &&
                    [[ $assignment_name =~ $credential_keyword ]]; then
                cursor=0
                if ! { [[ ${assignment_name,,} == *_count && $rhs =~ $integer_rhs ]] ||
                        { (( ! from_split )) &&
                          [[ $command_prefix != env || $word =~ $env_assignment ]] &&
                          credential_assignment_has_tilde "$rhs"; } ||
                        expansion_only; }; then
                    if [[ $command_prefix == env ]]; then
                        env_assignments+=("$start")
                    else
                        report_credential_assignment "$start"
                        reported=1
                    fi
                fi
            fi
            continue
        fi
        if (( declaration && ! word_expansion && ! function_declaration )); then
            if [[ $literal =~ ^[-+][a-zA-Z]+$ ]]; then
                if (( declaration == 1 )) && [[ $declaration_attributes == -x* && $literal == -*n* ]]; then
                    declaration_attributes+=' +x'
                else
                    declaration_attributes+=" $literal"
                fi
            elif [[ $literal =~ $identifier ]]; then
                apply_credential_attributes "$literal" "$local_declaration"
            fi
        fi
        if (( local_declaration && ! word_expansion )) && [[ $literal == -[a-zA-Z]*g* || $literal == -g* ]]; then
            local_declaration=0
        fi
        if (( declaration == 2 && ! word_expansion )) && [[ $literal =~ ^-[a-zA-Z]*r[a-zA-Z]*$ ]]; then
            readonly_declaration=1
        fi
        if (( declaration && ! word_expansion )) && [[ $literal =~ ^-[a-zA-Z]*f[a-zA-Z]*$ ]]; then
            function_declaration=1
        fi
        if (( readonly_declaration && ! word_expansion )) && [[ $literal =~ $identifier ]]; then
            if (( function_declaration )); then
                if [[ -n ${credential_functions[$literal]:-} ]]; then
                    change_credential_state "readonly:function:$literal" 1
                fi
            else
                mark_credential_readonly "$literal" "$local_declaration" "$nameref_declaration"
            fi
        fi
        if (( declaration == 2 && ! word_expansion )); then
            if [[ $literal =~ ^-[a-zA-Z]*i[a-zA-Z]*$ ]]; then integer_declaration=1
            elif [[ $literal =~ ^[+][a-zA-Z]*i[a-zA-Z]*$ ]]; then integer_declaration=-1
            elif (( integer_declaration )) && [[ $literal =~ $identifier ]]; then
                local name=$literal index=''
                if (( ! local_declaration )); then resolve_credential_nameref || continue; fi
                change_credential_state "integer:$name" "$((integer_declaration == 1))" "$local_declaration"
                if [[ ! ${variable_facts[$name]+present} ]]; then
                    change_credential_state "fact:$name" unset "$local_declaration"
                fi
            fi
        fi
        if (( declaration == 2 && ! word_expansion )) && [[ $literal =~ ^-[a-zA-Z]*n[a-zA-Z]*$ ]]; then
            nameref_declaration=1
        fi
        if (( declaration == 2 && ! word_expansion )) && [[ $literal =~ ^-[a-zA-Z]*A[a-zA-Z]*$ ]]; then
            associative_declaration=1
        fi
        if (( declaration == 2 && ! nameref_declaration && ! readonly_declaration && ! integer_declaration && ! word_expansion )) &&
                [[ $literal =~ $identifier && ${variable_facts[$literal]:-} == nameref:* ]]; then
            change_credential_state "fact:$literal" unknown "$local_declaration"
        fi
        if (( associative_declaration && ! word_expansion )) && [[ $literal =~ $identifier ]]; then
            local existing_fact=${variable_facts[$literal]:-unknown}
            if [[ $existing_fact != assoc-array:* ]]; then
                change_credential_state "fact:$literal" 'assoc-array:0:0' "$local_declaration"
            fi
        elif (( local_declaration && ! word_expansion )) && [[ $literal =~ $identifier ]]; then
            # A declaration without an initializer can shadow an outer value.
            if [[ $(credential_fact_is_local "fact:$literal") == 0 ]]; then
                if (( ! integer_declaration )); then change_credential_state "integer:$literal" 0 1; fi
                fact=unknown
                if (( integer_declaration == 1 )); then fact=unset; fi
                if [[ $literal == @(OLDPWD|HOME) ]]; then fact=''; fi
                change_credential_state "fact:$literal" "$fact" 1
            fi
        fi
        if (( command_position )); then
            if [[ $command_prefix == env ]] && (( word_expansion )) &&
                    resolve_env_word; then
                word_expansion=0
            fi
            if { [[ $command_prefix != env ]] ||
                    { [[ $literal == -* ]] && (( prefix_options || ! env_operands_started )); }; } &&
                    consume_command_prefix "$literal" "$word_expansion"; then
                if [[ -n $prefix_split ]]; then
                    split_start=$start
                    split_env_words "$prefix_split" || :
                    prefix_split=''
                fi
                continue
            fi
            if [[ $command_prefix == env ]]; then
                if (( word_expansion )); then
                    # A fixed long option name can be rejected even if its value is unknown.
                    if (( ! prefix_options )) || [[ $literal != --[a-z-]*=* ||
                            ${literal%%=*} == *"$env_expansion_marker"* ]]; then continue; fi
                fi
                # Once the command starts, its arguments belong to the command.
                if (( env_null )) || { (( prefix_options )) && [[ $literal == -* ]]; }; then
                    env_rejected=1
                fi
                finish_credential_command
                command_position=0
                continue
            fi
            if [[ -z $command_prefix ]] && [[ $word =~ $function_name ]] &&
                    { (( function_name_pending )) || [[ ${source:position} =~ $function_signature ]]; }; then
                if [[ ${source:position} =~ $function_signature ]]; then
                    position=$((position + ${#BASH_REMATCH[0]}))
                fi
                function_name_pending=0
                function_body_pending=$word
                continue
            fi
            if [[ $command_prefix == '' || $command_prefix == time ]] &&
                    (( ! word_expansion )) && [[ $literal =~ $function_name ]] &&
                    [[ -n ${credential_functions[$literal]:-} ]]; then
                pending_function=$literal
                command_position=0
                continue
            fi
            if (( ! word_expansion )) && [[ $command_prefix == @(''|time|command|builtin) ]]; then
                case $literal in
                    :|true|false|echo|printf|cat|cd|read|getopts|rm|mkdir|touch|cp|mv|install|ln|unlink|rmdir|truncate|chmod|chown|chgrp) output_command=$literal ;;
                esac
            fi
            if (( ! dead_branches && ! ${redirection_preflight:-0} )) &&
                    [[ $literal == @(export|readonly|local|declare|typeset|unset|set|shopt|printf|let|mkdir) &&
                       ( ${source:position} == *'<'* || ${source:position} == *'>'* ) ]] &&
                    credential_redirections_fail >/dev/null; then
                suppress_command_writes=1
            fi
            case $word in
                '{')
                    if [[ -n $function_body_pending ]]; then
                        enter_credential_scope function-brace "$start"
                        function_body_pending=''
                    else
                        enter_credential_scope brace "$start"
                    fi
                    continue
                    ;;
                '}') leave_credential_scope brace ;;
                function) function_name_pending=1; continue ;;
                if|while|until)
                    if [[ $word == if ]]; then enter_credential_scope if "$start"; else enter_credential_scope loop "$start"; fi
                    if_conditions+=(waiting-command)
                    if_dead+=(0)
                    if_negated+=(0)
                    condition_kind+=("$word")
                    if [[ $word == @(while|until) ]]; then
                        loop_condition_starts[${isolated_scopes[-1]}]=$logical_start:$position
                        loop_until[${isolated_scopes[-1]}]=0
                        if [[ $word == until ]]; then loop_until[${isolated_scopes[-1]}]=1; fi
                    fi
                    if [[ $word == if ]]; then condition_end+=(then); else condition_end+=(do); fi
                    continue
                    ;;
                then)
                    if [[ ${condition_kind[@]: -1} != if ]]; then continue; fi
                    local if_scope=${isolated_scopes[-1]}
                    certain_if_arms[$if_scope]=0
                    case ${if_conditions[@]: -1} in
                        waiting-false) if_conditions[-1]=false; set_credential_if_dead 1 ;;
                        waiting-true)
                            if_conditions[-1]=true
                            certain_if_arms[$if_scope]=$(( ! ${unknown_if_arms[$if_scope]:-0} ))
                            ;;
                        waiting-skipped) if_conditions[-1]=true ;;
                        waiting-*) if_conditions[-1]=unknown; unknown_if_arms[$if_scope]=1; scope_stdout[$if_scope]=unknown ;;
                    esac
                    continue
                    ;;
                elif|else)
                    if [[ ${condition_kind[@]: -1} == if ]]; then
                        local if_scope=${isolated_scopes[-1]}
                        clear_credential_abort "$if_scope"
                        certain_if_arms[$if_scope]=0
                        if [[ ${if_conditions[-1]} == true ]]; then
                            set_credential_if_dead 1
                            if [[ $word == elif ]]; then if_conditions[-1]=waiting-skipped; fi
                        else
                            set_credential_if_dead 0
                            if [[ $word == elif ]]; then
                                if_conditions[-1]=waiting-command
                                if_negated[-1]=0
                            elif [[ ${if_conditions[-1]} == false ]]; then
                                certain_if_arms[$if_scope]=$(( ! ${unknown_if_arms[$if_scope]:-0} ))
                            fi
                        fi
                    fi
                    continue
                    ;;
                do)
                    local loop_scope=${isolated_scopes[-1]} loop_input
                    loop_condition_ends[$loop_scope]=$logical_start:$start
                    if [[ ${if_conditions[@]: -1} == waiting-unknown ]]; then
                        scope_stdout[$loop_scope]=unknown
                        unset 'loop_condition_reads[$loop_scope]' 'loop_streams[$loop_scope]'
                    fi
                    if [[ ${loop_eof[$loop_scope]+present} && ${condition_kind[-1]} == while ]]; then
                        set_credential_if_dead 1
                    fi
                    case ${condition_kind[@]: -1}:${if_conditions[@]: -1} in
                        while:waiting-false|until:waiting-true) set_credential_if_dead 1 ;;
                        for:for-empty|select:for-empty)
                            empty_loop_bodies[$loop_scope]=1
                            set_credential_if_dead 1
                            ;;
                        for:for-name|select:for-name)
                            if [[ ${condition_kind[-1]} == for ]]; then loop_uses_positionals[$loop_scope]=1; fi
                            if (( positional_empty )); then
                                empty_loop_bodies[$loop_scope]=1
                                set_credential_if_dead 1
                            elif [[ ${condition_kind[-1]} == for && -n $positional_bounds ]]; then
                                local positional_first=${positional_bounds%:*}
                                append_credential_for_items "$loop_scope" \
                                    "${known_array_words[@]:positional_first:${positional_bounds#*:}-positional_first}"
                            fi
                            ;;
                    esac
                    if [[ ${condition_kind[-1]} == select ]]; then
                        credential_descriptor_source 0
                        loop_input=${input_source#stream:}
                        if [[ $input_source == stream:* && -z ${credential_streams[loop_input]} ]]; then
                            empty_loop_bodies[$loop_scope]=1
                            set_credential_if_dead 1
                        fi
                    fi
                    if [[ ${condition_kind[@]: -1} == @(while|until|for|select) ]]; then
                        case ${condition_kind[-1]}:${if_conditions[-1]} in
                            for:for-arithmetic)
                                certain_loop_bodies[$loop_scope]=1
                                loop_body_sites[$loop_scope]=$logical_start:$position
                                ;;
                            while:waiting-true|until:waiting-false) certain_loop_bodies[$loop_scope]=1 ;;
                        esac
                        if [[ ( ${loop_streams[$loop_scope]+present} || ${loop_comparisons[$loop_scope]+present} || ${loop_condition_commands[$loop_scope]+present} ) &&
                                ${certain_loop_bodies[$loop_scope]+present} ]]; then
                            loop_body_sites[$loop_scope]=$logical_start:$position
                        fi
                        if_conditions[-1]=loop-body
                        if [[ ${loop_names[$loop_scope]:-} =~ $identifier ]]; then
                            fact=unknown
                            if (( ${loop_list_count[$loop_scope]:-0} > 0 )); then
                                fact=${loop_list_items[$loop_scope:1]}
                                loop_body_sites[$loop_scope]=$logical_start:$position
                                certain_loop_bodies[$loop_scope]=1
                            fi
                            if [[ ${condition_kind[-1]} == select && ! ${unknown_select_lists[$loop_scope]+present} ]]; then
                                credential_input_source
                                loop_input=${input_source#stream:}
                                local selection='^[[:blank:]]*[+]?0*([1-9][0-9]*)[[:blank:]]*'$'\n''$'
                                if [[ $input_source == stream:* && ${credential_streams[loop_input]} =~ $selection ]]; then
                                    fact=${select_items[$loop_scope:${BASH_REMATCH[1]}]:-unknown}
                                    if (( ! dead_branches )); then credential_streams[loop_input]=''; fi
                                fi
                            fi
                            change_credential_state "fact:${loop_names[$loop_scope]}" "$fact"
                        fi
                    fi
                    if (( ! dead_branches )) && [[ ! ${loop_body_sites[$loop_scope]+present} ]]; then
                        scope_stdout[$loop_scope]=unknown
                    fi
                    continue
                    ;;
                fi|done)
                    if [[ $word:${condition_kind[@]: -1} == @(fi:if|done:while|done:until|done:for|done:select) ]]; then
                        if [[ $word == done ]]; then
                            replay_credential_loop_body "$logical_start:$start"
                            if [[ ${loop_has_break[${isolated_scopes[-1]}]+present} ]]; then
                                dead_branches=$((dead_branches - 1))
                            fi
                        fi
                        set_credential_if_dead 0
                        unset 'if_conditions[-1]' 'if_dead[-1]' 'if_negated[-1]' 'condition_kind[-1]' 'condition_end[-1]'
                        if [[ $word == fi ]]; then
                            leave_credential_scope if
                        else
                            finish_credential_loop
                            leave_credential_scope loop
                        fi
                    fi
                    ;;
                '!') conditional_chain=1; command_negated=$(( ! command_negated )); continue ;;
                break|continue)
                    local control_end="^([[:blank:]]+($loop_level))?[[:blank:]]*(;|$|"$'\n'")"
                    if (( ! conditional_chain )) && [[ ${source:position} =~ $control_end ]]; then
                        pending_loop_action=$word
                        pending_loop_level=${BASH_REMATCH[2]}
                    fi
                    ;;
                return)
                    if (( function_depth )) && [[ $command_prefix == @(''|time|command|builtin) ]]; then
                        pending_return=$((start + 1))
                    fi
                    ;;
                for|select)
                    enter_credential_scope loop "$start"
                    if_conditions+=(loop-body)
                    if_dead+=(0)
                    if_negated+=(0)
                    condition_kind+=("$word")
                    condition_end+=(do)
                    if [[ $word == for ]]; then for_header=1; else select_header=1; fi
                    ;;
                '[[') conditional=1 ;;
                case)
                    enter_credential_scope case "$start"
                    case_index=${#case_stack[@]}
                    return_case_indices[${isolated_scopes[-1]}]=$case_index
                    case_stack+=(subject)
                    case_known[case_index]=0
                    case_possible[case_index]=0
                    case_certain[case_index]=0
                    case_finished[case_index]=0
                    case_prior_possible[case_index]=0
                    ;;
                'esac')
                    if [[ ${case_stack[-1]} == body ]]; then leave_credential_case; fi
                    leave_credential_scope case
                    ;;
                export) declaration=1 declaration_attributes=-x; validate_credential_declaration ;;
                readonly) declaration=1 readonly_declaration=1; validate_credential_declaration ;;
                local|declare|typeset)
                    declaration=2
                    validate_credential_declaration
                    local_declaration=$((function_depth > 0))
                    if [[ $word == local ]] && (( ! function_depth )); then
                        declaration_invalid=1 declaration_no_writes=1
                    fi
                    if (( local_declaration )) && [[ $word == local ]]; then local_builtin=1; fi
                    ;;
                mapfile|readarray) map_builtin=1 ;;
                shopt) shopt_options=1 ;;
                unset) unset_arguments=1 ;;
                mkdir) mkdir_arguments=1 ;;
                set) set_options=1 ;;
                getopts) getopts_builtin=1 ;;
                shift)
                    if [[ $command_prefix == @(''|time|command|builtin) ]]; then shift_builtin=1; fi
                    ;;
                let|\"let\"|"'let'")
                    if [[ -z $command_prefix || $command_prefix == @(time|command|builtin) ]]; then
                        let_arguments=1
                    fi
                    ;;
                printf)
                    if credential_printf_invalid_format; then builtin_failed=1; fi
                    if credential_printf_readonly_target; then printf_readonly=1; fi
                    if (( ! allowed && ! reported )) && printf_assignment_has_literal; then
                        report_credential_assignment "$start"
                        reported=1
                    fi
                    # The format checker also reads words. Resume from the command.
                    position=$((start + ${#word}))
                    ;;
                *)
                    if (( ! word_expansion )); then
                        if [[ $literal == cd && $command_prefix == @(''|time|command|builtin) ]]; then
                            if credential_cd_may_succeed; then oldpwd_effect=set; fi
                        elif [[ $literal == read && $command_prefix == @(''|time|command|builtin) ]]; then
                            read_builtin=1
                        elif (( function_depth )) && [[ $command_prefix == @(''|time) && $literal =~ $function_name ]]; then
                            pending_function=$literal
                        fi
                    fi
                    ;;
            esac
        fi
        command_position=0
    done
    finish_credential_command
    if (( ! command_position )); then pipeline_next=0; fi
}

# Cache logical source and sparse offset tables so call-site scans use
# the original line numbers and the parser's substitution boundaries.
remember_credential_source() {
    local offset
    credential_source[$logical_start]=$logical
    for offset in "${!continued_offsets[@]}"; do
        source_line_offsets[$logical_start:${continued_offsets[offset]}]=${continued_lines[offset]}
        source_line_breaks[$logical_start]+=" ${continued_offsets[offset]}"
    done
    for offset in "${!substitution_ends[@]}"; do
        source_substitutions[$logical_start:$offset]=${substitution_ends[offset]}
        source_substitution_starts[$logical_start]+=" $offset"
    done
    for offset in "${!arithmetic_ends[@]}"; do
        source_arithmetic[$logical_start:$offset]=${arithmetic_ends[offset]}
        source_arithmetic_starts[$logical_start]+=" $offset"
    done
    for offset in "${!backtick_ends[@]}"; do
        source_backticks[$logical_start:$offset]=${backtick_ends[offset]}
        source_backtick_starts[$logical_start]+=" $offset"
    done
}

diagnose_credential_function() (
    local summary=$1 first=${definition_starts[$1]} last=${definition_ends[$1]}
    local credential_subshell_context=${credential_subshell_context:-0}
    if credential_has_readonly_abort_scope; then credential_subshell_context=1; fi
    local function_depth=$summary diagnostic_function=$summary for_iteration_path=''
    local replaying_readonly_call=0
    local function_name_pending=0 function_body_pending=''
    local -a isolated_scopes=("$summary") credential_scopes=("function-brace")
    local -a case_stack=(outside) case_subject=() case_known=() case_possible=() case_certain=() case_finished=() case_prior_possible=()
    local -a if_conditions=() if_dead=() if_negated=() condition_kind=() condition_end=()
    local dead_branches=0 pipeline_next=0 positional_empty=$positional_empty positional_bounds=$positional_bounds
    local suppress_credential_reports=0
    local -A returned_functions=() readonly_abort_scopes=() aborted_credential_shells=()
    scope_inputs[$summary]=$2
    scope_replay_inputs[$summary]=$2
    local descriptor
    for descriptor in "${!function_call_fds[@]}"; do
        scope_descriptors[$summary:$descriptor]=${function_call_fds[$descriptor]}
    done
    printf 'call %s\n' "$first"
    scan_credential_range "$first" "$last"
)

# Scan a source range with the original line mapping and expansion boundaries.
scan_credential_range() {
    local first=$1 last=$2 logical_start logical offset start end offsets
    local heredoc_mode=0
    local -a continued_offsets continued_lines substitution_ends arithmetic_ends backtick_ends
    for (( logical_start=${first%:*}; logical_start<=${last%:*}; logical_start++ )); do
        [[ ${credential_source[$logical_start]+present} ]] || continue
        logical=${credential_source[$logical_start]}
        start=0 end=${#logical}
        if (( logical_start == ${first%:*} )); then start=${first#*:}; fi
        if (( logical_start == ${last%:*} )); then end=${last#*:}; fi
        (( start < end )) || continue
        logical=${logical:0:end}
        continued_offsets=() continued_lines=() substitution_ends=() arithmetic_ends=() backtick_ends=()
        IFS=' ' read -r -a offsets <<< "${source_line_breaks[$logical_start]:-}"
        for offset in "${offsets[@]}"; do
            continued_offsets+=("$offset")
            continued_lines+=("${source_line_offsets[$logical_start:$offset]}")
        done
        IFS=' ' read -r -a offsets <<< "${source_substitution_starts[$logical_start]:-}"
        for offset in "${offsets[@]}"; do
            if (( start <= offset && offset < end )); then
                substitution_ends[offset]=${source_substitutions[$logical_start:$offset]}
            fi
        done
        IFS=' ' read -r -a offsets <<< "${source_arithmetic_starts[$logical_start]:-}"
        for offset in "${offsets[@]}"; do
            if (( start <= offset && offset < end )); then
                arithmetic_ends[offset]=${source_arithmetic[$logical_start:$offset]}
            fi
        done
        IFS=' ' read -r -a offsets <<< "${source_backtick_starts[$logical_start]:-}"
        for offset in "${offsets[@]}"; do
            if (( start <= offset && offset < end )); then
                backtick_ends[offset]=${source_backticks[$logical_start:$offset]}
            fi
        done
        scan_credential_logical_line "$start"
    done
}

scan_credential_logical_line() {
    local opening source=$logical position
    if [[ ${aborted_credential_shells[0]+present} ]]; then return 0; fi
    local -a scanned_substitutions=() scanned_backticks=() skipped_arithmetic=()
    if (( ! heredoc_mode )); then
        if [[ ! ${credential_source[$logical_start]+present} ]]; then remember_credential_source; fi
        scan_credential_statement "$logical" "${1:-0}"
    elif ! credential_line_allowed "$logical"; then
        local scan_word_substitutions=1 allowed=0
        position=0
        for opening in "${heredoc_parameters[@]}"; do
            if (( opening < position )); then continue; fi
            position=$((opening + 2))
            read_parameter_word "$opening" || :
        done
    fi
    heredoc_parameters=()
    # Substitution bounds and line mappings refer to the same logical source.
    for opening in "${!substitution_ends[@]}"; do
        [[ -n ${substitution_ends[opening]:-} && -z ${scanned_substitutions[opening]:-} ]] || continue
        scan_credential_substitution "$opening" "${capture_heredoc_output:+capture}"
    done
    if ! credential_line_allowed "$logical"; then
        for opening in "${!arithmetic_ends[@]}"; do
            [[ -z ${skipped_arithmetic[opening]:-} ]] || continue
            scan_credential_arithmetic "$((opening + 3))" "$((arithmetic_ends[opening] - 1))"
        done
        for opening in "${!backtick_ends[@]}"; do
            [[ -z ${scanned_backticks[opening]:-} ]] || continue
            scan_credential_backtick "$opening" "${backtick_ends[opening]}"
        done
    fi
    substitution_ends=()
    arithmetic_ends=()
    backtick_ends=()
}

# Scan substitutions with the facts and options visible at their source word.
# The logical-line pass still handles expansions in opaque contexts such as heredocs.
scan_credential_word_substitution() {
    local opening=$1 kind=$2 saved_match=("${BASH_REMATCH[@]}")
    (( ${scan_word_substitutions:-0} )) || return 0
    if (( ${#aborted_credential_shells[@]} )); then
        if [[ $kind == backtick ]]; then
            scanned_backticks[opening]=1
        else
            scanned_substitutions[opening]=1
        fi
        return 0
    fi
    if [[ $kind == backtick ]]; then
        [[ -z ${scanned_backticks[opening]:-} ]] || return 0
        scanned_backticks[opening]=1
        local substitution_value=unknown substitution_status=2
        if (( ! allowed )); then scan_credential_backtick "$opening" "${backtick_ends[opening]}"; fi
        substitution_values[${source:opening:backtick_ends[opening]+1-opening}]=$substitution_value
        substitution_statuses[${source:opening:backtick_ends[opening]+1-opening}]=$substitution_status
    else
        [[ -z ${scanned_substitutions[opening]:-} ]] || return 0
        scanned_substitutions[opening]=1
        local substitution_value=unknown substitution_status=2
        scan_credential_substitution "$opening" "${capture_heredoc_output:+capture}"
        substitution_values[${source:opening:substitution_ends[opening]+1-opening}]=$substitution_value
        substitution_statuses[${source:opening:substitution_ends[opening]+1-opening}]=$substitution_status
    fi
    BASH_REMATCH=("${saved_match[@]}")
}

scan_credential_substitution() {
    local compound_stdout='' substitution_depth=$((${substitution_depth:-0} + 1))
    (( substitution_depth <= 32 )) || return 0
    local opening=$1 scope_count=${#credential_scopes[@]}
    local credential_report_owner=${function_depth:-${credential_report_owner:-0}}
    local function_depth=0 function_name_pending=0 function_body_pending=''
    local -a case_stack=(outside) case_subject=() case_known=() case_possible=() case_certain=() case_finished=() case_prior_possible=()
    local -a if_conditions=() if_dead=() if_negated=() condition_kind=() condition_end=()
    local dead_branches=0 pipeline_next=0 substitution_last_status=2
    enter_credential_scope paren
    local substitution_output_scope=${isolated_scopes[-1]} substitution_output
    local replaying_readonly_call=0
    if [[ ${2:-} == backtick ]]; then
        scan_credential_statement "${logical:0:backtick_ends[opening]}" "$((opening + 1))" 1
    else
        scan_credential_statement "${logical:0:substitution_ends[opening]}" "$((opening + 2))" 1
    fi
    substitution_status=$substitution_last_status
    if [[ ${aborted_credential_shells[$substitution_output_scope]+present} ]]; then substitution_status=1; fi
    substitution_output=${scope_stdout[$substitution_output_scope]:-unknown}
    if [[ $substitution_output == literal:* ]]; then
        substitution_output=scalar:${substitution_output#literal:}
        if [[ ${2:-} != stream ]]; then
            while [[ $substitution_output == *$'\n' ]]; do substitution_output=${substitution_output%$'\n'}; done
        fi
        substitution_value=$substitution_output
    fi
    # Restore the substitution's entry state, including any nested scopes.
    while (( ${#credential_scopes[@]} > scope_count )); do
        case ${credential_scopes[-1]} in
            *paren) leave_credential_scope paren ;;
            *) leave_credential_scope "${credential_scopes[-1]#function-}" ;;
        esac
    done
}

scan_credential_backtick() {
    local opening=$1 end=$2 i char body='' credential_line offset_index
    local encoded=${logical:opening+1:end-opening-1}
    # Simple backquotes share the scoped producer model. Escaped backquotes,
    # dollars and backslashes need their separate legacy decoding pass; keep
    # the resulting value unknown rather than reinterpret their quoting.
    if [[ $encoded != *'\`'* && $encoded != *'\$'* && $encoded != *'\\'* ]]; then
        scan_credential_substitution "$opening" backtick
        return
    fi
    local next_boundary=0
    credential_source_line "$opening"
    # Restore escaped newlines to keep the recursive scan's line offsets.
    for (( i=opening+1; i<end; i++ )); do
        while (( next_boundary < ${#continued_offsets[@]} &&
                  continued_offsets[next_boundary] <= i )); do
            offset_index=${continued_offsets[next_boundary]}
            if (( offset_index == i )) && [[ ${logical:i-1:1} != $'\n' ]]; then
                body+=$'\\\n'
            fi
            next_boundary=$((next_boundary + 1))
        done
        char=${logical:i:1}
        if [[ $char == '\' && ${logical:i+1:1} == [\$\`\\] ]] &&
                [[ ${continued_offsets[next_boundary]:-} != "$((i + 1))" ]]; then
            i=$((i + 1))
            char=${logical:i:1}
        fi
        body+=$char
    done
    scan_credentials "$credential_line" <<< "$body"
}

scan_credentials() {
    local -A compound_redirections=() compound_redirection_origins=() compound_inputs=() compound_descriptor_inputs=() compound_stream_contents=() detached_compound_sites=()
    local discovering_credential_inputs=${discovering_credential_inputs:-0}
    local body trailer='([})]|done|esac|fi)[[:space:]]*([0-9]*[<>]|[|&])'
    # Trailing redirects, pipes and background operators appear after the
    # enclosed commands. Discover both input and execution boundaries first.
    body=$(cat)
    if [[ $body =~ $trailer ]]; then
        discovering_credential_inputs=1 scan_credential_stream "$@" <<< "$body"
    fi
    scan_credential_stream "$@" <<< "$body"
}

scan_credential_stream() {
    local physical code continues=0 quote='' closers='' word_start=1 scan_word_substitutions=0
    local continued_loop_read='(^|[;{[:space:]])(while|until)[[:blank:]]+read([[:blank:]].*)?(&&|\|\|)[[:blank:]]*$'
    local continued_read='^[[:blank:]]*read([[:blank:]].*)?(&&|\|\|)[[:blank:]]*$'
    local heredoc_mode=${2:-0} source_first_line=${1:-1}
    local -a source_physical_lines=()
    mapfile -t source_physical_lines <<< "$body"
    local pipeline_next=0 next_function_effect=0 replaying_function=0 readonly_call_failed=0
    local -A builtin_chain_operator=() builtin_chain_status=()
    local builtin_skip=0 builtin_skip_depth=0 builtin_skip_status=2
    local readonly_chain_skip=0 readonly_chain_depth=0 command_negated=0
    local nocasematch=${nocasematch:-0} lastpipe=${lastpipe:-0} monitor_mode=${monitor_mode:-0} nullglob=${nullglob:-0}
    local dotglob=${dotglob:-0} credential_directory=${credential_directory-$PWD/${path%/*}}
    local credential_created_directories=${credential_created_directories:-}
    local positional_empty=${positional_empty:-1} positional_bounds='' active_function_calls=:
    local pipeline_input=unknown heredoc_body_cursor=0 getopts_offset=1
    local -A unknown_streams=() persistent_descriptors=() scope_descriptors=() scope_stdout=() registered_heredocs=() registered_heredoc_quotes=()
    local -a credential_streams=() known_array_indices=() known_array_words=() unknown_array_elements=()
    local -A sparse_array_bounds=()
    local noclobber=${noclobber:-0}
    local noglob=${noglob:-0} patsub_replacement=${patsub_replacement:-1}
    local braceexpand=${braceexpand:-1} next_credential_scope=0 dead_branches=0
    local -a credential_scopes=() isolated_scopes=(0) if_conditions=() if_dead=() if_negated=() condition_kind=() condition_end=()
    local -a case_subject=() case_known=() case_possible=() case_certain=() case_finished=() case_prior_possible=()
    local -A readonly_abort_scopes=() aborted_credential_shells=() function_may_abort=()
    local -A saved_state=() scope_writes=() local_facts=() function_scopes=() compound_scopes=()
    local -A credential_functions=() definition_names=() function_writes=()
    local -A opened_compound_redirections=()
    local -A failed_compound_scopes=() function_definition_redirections=() function_definition_redirection_origins=() function_replay_bodies=() function_call_fds=()
    local -A scope_sites=() scope_inputs=() scope_replay_inputs=() function_definition_inputs=()
    local -A credential_file_ids=() credential_file_contents=()
    local -A function_read_names=()
    local -a function_read_counts=() function_read_options=() function_read_arrays=()
    local -a function_read_delimiters=() function_read_ifs=() function_call_inputs=() function_call_detached=()
    local -a function_call_positionals=()
    local -a function_effects=() function_keys=() function_values=() function_locals=()
    local -a function_parameter_lines=()
    local diagnostic_function=0 replaying_loop_function=0 suppress_credential_reports=0 for_iteration_path=''
    local -A certain_if_arms=() unknown_if_arms=() return_case_indices=() conditional_function_returns=()
    local -A called_functions=() returned_functions=() definition_lines=() definition_starts=() definition_ends=()
    local -A credential_source=() source_line_breaks=() source_line_offsets=()
    local -A source_substitution_starts=() source_substitutions=()
    local -A source_arithmetic_starts=() source_arithmetic=() source_backtick_starts=() source_backticks=()
    local -A loop_condition_reads=() loop_list_count=() loop_has_break=() loop_has_continue=()
    local -A loop_comparisons=() loop_uses_positionals=() loop_condition_commands=()
    local -A loop_condition_starts=() loop_condition_ends=()
    local -A loop_arithmetic_conditions=() loop_arithmetic_steps=()
    local -A loop_tests=() loop_until=() loop_streams=() loop_eof=()
    local -A loop_prefix_tests=() loop_prefix_operator=()
    local -A loop_list_items=() loop_body_sites=() certain_loop_bodies=() empty_loop_bodies=()
    local -A loop_names=() select_item_counts=() select_items=() unknown_select_lists=()
    local function_depth=0 function_name_pending=0 function_body_pending=''
    local inherited_oldpwd='' inherited_ifs='' inherited_home=''
    if (( $# )); then
        inherited_oldpwd=${variable_facts[OLDPWD]:-}
        inherited_ifs=${variable_facts[IFS]:-}
        inherited_home=${variable_facts[HOME]:-}
    elif [[ ${HOME+x} ]]; then
        inherited_home=scalar:$HOME
    fi
    local -A variable_facts=() readonly_facts=() attribute_facts=() integer_facts=() substitution_values=() substitution_statuses=()
    variable_facts[OPTIND]=scalar:1
    local -a env_expansion_quotes=()
    local -a function_fatal_writes=()
    if [[ -n $inherited_oldpwd ]]; then variable_facts[OLDPWD]=$inherited_oldpwd; fi
    if [[ -n $inherited_ifs ]]; then variable_facts[IFS]=$inherited_ifs; fi
    if [[ -n $inherited_home ]]; then variable_facts[HOME]=$inherited_home; fi
    if (( heredoc_mode )); then quote=heredoc; fi
    local logical='' line=$((${1:-1} - 1)) logical_start=${1:-1}
    if (( ! $# )); then local -a reported_lines=(); fi
    local -a quoted_parameters=() expansion_quotes=() case_stack=(outside) continued_offsets=() continued_lines=()
    local -a heredoc_delimiters=() heredoc_strip_tabs=() heredoc_quoted=() condition_levels=()
    local -a physical_cases=() physical_commands=() pattern_groups=()
    local -a substitution_starts=() substitution_ends=() arithmetic_starts=() arithmetic_ends=()
    local -a backtick_ends=() heredoc_parameters=()
    local backtick_start backtick_quote
    local heredoc_head=0 heredoc_line='' pending_heredoc='' prefix
    local heredoc_body='' heredoc_start heredoc_chunk=''
    # Only an odd run of trailing backslashes escapes the newline.
    local joins_next='(^|[^\\])(\\\\)*\\$'

    while IFS= read -r physical || [[ -n $physical ]]; do
        line=$((line + 1))
        if (( ! continues && heredoc_head < ${#heredoc_delimiters[@]} )) &&
                [[ -z $pending_heredoc && -z $quote ]]; then
            # Skipped body lines shift the next source location but add no code.
            if [[ -z $logical ]]; then
                logical_start=$((line + 1))
            else
                continued_lines[-1]=$((line + 1))
            fi
            if (( ! heredoc_quoted[heredoc_head] )); then
                if [[ -z $heredoc_body && -z $heredoc_chunk ]]; then heredoc_start=$line; fi
                heredoc_chunk+=$physical$'\n'
            fi
            heredoc_line+=$physical
            if (( ! heredoc_quoted[heredoc_head] )) && [[ $physical =~ $joins_next ]]; then
                heredoc_line=${heredoc_line%\\}
                continue
            fi
            if (( heredoc_strip_tabs[heredoc_head] )); then
                while [[ $heredoc_line == $'\t'* ]]; do
                    heredoc_line=${heredoc_line#$'\t'}
                done
            fi
            if [[ $heredoc_line == "${heredoc_delimiters[heredoc_head]}" ]]; then
                if [[ -n $heredoc_body ]]; then
                    scan_credentials "$heredoc_start" 1 <<< "${heredoc_body%$'\n'}"
                fi
                heredoc_body=''
                heredoc_head=$((heredoc_head + 1))
                if (( heredoc_head == ${#heredoc_delimiters[@]} )); then
                    heredoc_delimiters=()
                    heredoc_strip_tabs=()
                    heredoc_quoted=()
                    heredoc_head=0
                fi
            else
                heredoc_body+=$heredoc_chunk
            fi
            heredoc_chunk=''
            heredoc_line=''
            continue
        fi
        # Rescan a delimiter word split across physical lines. The prefix is
        # already in the logical statement, so append only the new input.
        prefix=$pending_heredoc
        pending_heredoc=''
        strip_credential_comment "$prefix$physical"
        code=${code:${#prefix}}
        if (( continues )); then
            logical+=${code%\\}
            continued_offsets+=("${#logical}")
            continued_lines+=("$((line + 1))")
        elif [[ ( -n $quote && $quote != heredoc ) || -n $closers || -n $pending_heredoc ||
                $logical$code =~ $continued_loop_read ||
                ( ${condition_kind[@]: -1} == @(while|until) &&
                  ${if_conditions[@]: -1} == waiting-command && $code =~ $continued_read ) ]]; then
            logical+=$code$'\n'
            continued_offsets+=("${#logical}")
            continued_lines+=("$((line + 1))")
        else
            logical+=$code
            scan_credential_logical_line
            logical=''
            logical_start=$((line + 1))
            continued_offsets=()
            continued_lines=()
        fi
    done
    if [[ -n $logical ]]; then scan_credential_logical_line; fi
    local entry
    for entry in "${!definition_lines[@]}"; do
        local definition=${definition_starts[${entry%:*}]}
        if [[ ! ${called_functions[$definition]+present} ]]; then report_credential_line "${entry#*:}"; fi
    done
}

# Negative indexed subscripts are relative to one past the maximum key.
# Partial arrays cannot establish that maximum; associative keys stay literal.
credential_array_extent() {
    local bounds=${variable_facts[$1]:-unknown} offset key
    array_extent=0
    case $bounds in
        array:*)
            bounds=${bounds#*:}
            for (( offset=${bounds%:*}; offset<${bounds#*:}; offset++ )); do
                key=${known_array_indices[offset]}
                if (( key >= array_extent )); then array_extent=$((key + 1)); fi
            done ;;
        scalar:*|set) array_extent=1 ;;
        unset) ;;
        *) return 1 ;;
    esac
}

resolve_credential_subscript() {
    local subject=$1 reference=${2:1:-1} index_fact name
    local identifier='^[A-Za-z_][A-Za-z0-9_]*$'
    local -A visited=()
    resolved_subscript=$2
    if [[ ${variable_facts[$subject]:-} == assoc-array:* ]]; then
        local source=$reference']' position=0 literal='' word_expansion=0
        local -a env_expansions=() env_expansion_quotes=()
        resolved_subscript=''
        read_shell_word ']' 0 2 0 0 0 0 0 0 1
        if { (( ! word_expansion )) || resolve_env_word 1; } && [[ -n $literal ]]; then
            resolved_subscript=[$literal]
        fi
        return 0
    fi
    while :; do
        case $reference in
            '${'*'}') name=${reference:2:-1} ;;
            '$'*) name=${reference:1} ;;
            *) name=$reference ;;
        esac
        [[ $name =~ $identifier ]] || break
        [[ ! ${visited[$name]+present} ]] || return 0
        visited[$name]=1
        index_fact=${variable_facts[$name]:-}
        [[ $index_fact == scalar:* ]] || return 0
        reference=${index_fact#scalar:}
    done
    if [[ ! $reference =~ ^-?[0-9]{1,18}$ && $reference != @('@'|'*') ]]; then
        local subscript_value
        if subscript_value=$(credential_arithmetic_value "$reference" ':' "$(( ${arithmetic_depth:-0} + 1 ))"); then
            reference=$subscript_value
        fi
    fi
    if [[ $reference =~ ^-[0-9]{1,18}$ ]]; then
        local array_extent negative_value
        negative_value=$(credential_arithmetic_value "$reference") || { resolved_subscript=''; return; }
        reference=$negative_value
        # A syntactic minus does not make zero a relative-to-end index.
        if (( reference == 0 )); then resolved_subscript='[0]'; return; fi
        if credential_array_extent "$subject"; then
            reference=$((array_extent + reference))
            if (( reference < 0 )); then resolved_subscript=invalid-negative; return; fi
        else resolved_subscript=''; return
        fi
    fi
    resolved_subscript=[$reference]
}

credential_special_parameter_fact() {
    local name=$1 offset separator
    current=unknown
    case $name in
        '#')
            if [[ -n ${positional_bounds:-} ]]; then
                current=scalar:$(( ${positional_bounds#*:} - ${positional_bounds%:*} ))
            elif (( ${positional_empty:-0} )); then
                current=scalar:0
            fi
            return
            ;;
        '*'|'@')
            if (( ${positional_empty:-0} )); then
                current=unset
            elif [[ -n ${positional_bounds:-} ]]; then
                separator=${variable_facts[IFS]-unset}
                if [[ $name == '@' ]]; then separator=unset; fi
                case $separator in
                    unset) separator=' ' ;;
                    scalar:*) separator=${separator:7:1} ;;
                    *) return ;;
                esac
                local IFS=$separator
                offset=${positional_bounds%:*}
                local -a words=("${known_array_words[@]:offset:${positional_bounds#*:}-offset}")
                current=scalar:${words[*]}
            fi
            return
            ;;
    esac
    local number=${name#"${name%%[!0]*}"}
    if [[ -z $number ]]; then
        current=scalar:${path:-script}
        return
    fi
    if [[ -z ${positional_bounds:-} ]]; then
        if (( ${positional_empty:-0} )); then current=unset; fi
        return 0
    fi
    current=unset
    (( ${#number} <= 18 )) || return 0
    offset=$(( ${positional_bounds%:*} + number - 1 ))
    if (( offset < ${positional_bounds#*:} )); then
        current=scalar:${known_array_words[offset]}
    fi
}

# Follow only literal names and literal array subscripts; cycles stay unknown.
resolve_credential_pending_alias() {
    local slot=$1 name=${pending_names[$1]} index=${pending_indices[$1]}
    if resolve_credential_nameref; then
        if [[ ${variable_facts[${pending_names[slot]}]:-} == nameref:* &&
                -z $index && ${pending_facts[slot]} == scalar:* &&
                ${variable_facts[$name]:-} == @(array:*|partial-array:*|assoc-array:*) ]]; then
            # A scalar write through an array alias changes element zero only.
            index='[0]'
        fi
        pending_names[slot]=$name
        pending_indices[slot]=$index
    else
        pending_facts[slot]=unknown
    fi
}

resolve_credential_nameref() {
    local target seen=' ' depth=0
    while [[ ${variable_facts[$name]:-} == nameref:* ]]; do
        [[ $seen != *" $name "* ]] && (( depth++ < 32 )) || return 1
        seen+="$name "
        target=${variable_facts[$name]#nameref:}
        [[ $target =~ $assignment_target$ ]] || return 1
        name=${BASH_REMATCH[1]}
        if [[ -n ${BASH_REMATCH[2]} ]]; then
            [[ -z $index ]] || return 1
            index=${BASH_REMATCH[2]}
        fi
    done
}

credential_parameter_fact() {
    local name=$1 index=$2 bounds offset resolved_subscript
    resolve_credential_nameref || { current=unknown; return; }
    if [[ -z $index && $name =~ ^([0-9]+|[#*@])$ ]]; then
        credential_special_parameter_fact "$name"
        return 0
    fi
    if [[ -n $index ]] && (( ! ${3:-0} )); then
        resolve_credential_subscript "$name" "$index"
        index=$resolved_subscript
        if [[ -z $index ]]; then current=unknown; return; fi
        if [[ $index == invalid-negative ]]; then current=unset; return; fi
    fi
    current=${variable_facts[$name]:-unknown}
    if [[ -z $index ]]; then index='[0]'; fi
    case $current in
        array:*|partial-array:*|assoc-array:*)
            if [[ $current != assoc-array:* && ! $index =~ ^\[(0|[1-9][0-9]{0,17})\]$ ]]; then
                current=unknown
                return
            fi
            if [[ $index != \[?*\] ]]; then current=unknown; return; fi
            index=${index:1:-1}
            bounds=${current#*:}
            if [[ $current == array:* ]]; then current=unset; else current=unknown; fi
            for (( offset=${bounds%:*}; offset<${bounds#*:}; offset++ )); do
                if [[ ${known_array_indices[offset]} == "$index" ]]; then
                    current=scalar:${known_array_words[offset]}
                    current=${unknown_array_elements[offset]:-"$current"}
                    break
                fi
            done
            ;;
        scalar:*|set)
            if [[ $index =~ ^\[[1-9][0-9]*\]$ ]]; then current=unset
            elif [[ $index != '[0]' ]]; then current=unknown
            fi
            ;;
        array-unknown) current=unknown ;;
    esac
}

scan_credential_parameter() {
    local scope=${isolated_scopes[-1]} credential_line
    if (( ! replaying_function && ! dead_branches && ! ${skip_expansion_effects:-0} )) &&
            records_credential_effects "$scope"; then
        local replaying_function=1
        if ! read_parameter_word "$@"; then return 1; fi
        credential_source_line "$1"
        record_credential_function_effect "$scope" parameter "${source:$1:position-$1}" "$2" 0
        function_parameter_lines[next_function_effect]=$credential_line
    else
        read_parameter_word "$@"
    fi
}

replay_credential_parameter() {
    local source=$2 position=2 logical_start=${function_parameter_lines[$1]}
    local -a continued_offsets=() continued_lines=()
    local skip_expansion_effects=0 outer_default_glob=0 scan_word_substitutions=1
    read_parameter_word 0 "$3"
}

resolve_credential_parameter_value() {
    local source=$1 position=2 parameter_quoted=0
    if [[ ${2:-} == '"' ]]; then parameter_quoted=1; fi
    read_parameter_word 0 "$parameter_quoted" 0 1 && [[ $parameter_value == scalar:* && $position == "${#source}" ]]
}

# A default word is an expansion body, not a standalone shell word: spaces
# belong to its value, and outer double quotes keep single quotes literal.
resolve_credential_default_value() {
    local source=$1 position=0 literal='' word_expansion=0 part
    local parameter_value_depth=$(( ${parameter_value_depth:-0} + 1 ))
    (( parameter_value_depth < 32 )) || return 0
    local -a env_expansions=() env_expansion_quotes=()
    read_shell_word '}' 0 2 0 0 0 0 0 0 1 "$2"
    (( position == ${#source} )) || return 0
    if (( $2 )); then
        for part in "${!env_expansions[@]}"; do env_expansion_quotes[part]='"'; done
    fi
    if resolve_env_word 1 0 1; then parameter_value=scalar:$literal; fi
}

# Apply the pattern to each known element before joining the resulting words.
resolve_credential_pattern_list() {
    local name=$1 operator=$2 pattern=$3 replacement=$4 index=${5:-}
    local fact bounds offset
    local -a words=()
    if [[ $name == [@*] ]]; then
        if (( ! ${positional_empty:-0} )); then
            bounds=${positional_bounds:-}
            [[ -n $bounds ]] || return 0
        fi
    else
        if [[ -n $index && $index != @('[@]'|'[*]') ]]; then
            local current
            credential_parameter_fact "$name" "$index"
            fact=$current
        else
            fact=${variable_facts[$name]:-unknown}
        fi
        case $fact in
            array:*|assoc-array:*) bounds=${fact#*:} ;;
            scalar:*) words=("${fact#scalar:}") ;;
            unset) ;;
            *) return 0 ;;
        esac
    fi
    if [[ -n ${bounds:-} ]]; then
        for (( offset=${bounds%:*}; offset<${bounds#*:}; offset++ )); do
            [[ ! ${unknown_array_elements[offset]:-} ]] || return 0
            words+=("${known_array_words[offset]}")
        done
    fi
    if (( ${#words[@]} )); then
        case $operator in
            '#') words=("${words[@]#$pattern}") ;;
            '##') words=("${words[@]##$pattern}") ;;
            '%') words=("${words[@]%$pattern}") ;;
            '%%') words=("${words[@]%%$pattern}") ;;
            '^') words=("${words[@]^$pattern}") ;;
            '^^') words=("${words[@]^^$pattern}") ;;
            ',') words=("${words[@],$pattern}") ;;
            ',,') words=("${words[@],,$pattern}") ;;
            *)
                # Keep the scanned script's option isolated from the checker.
                mapfile -d '' -t words < <(
                    if (( ${patsub_replacement:-1} )); then
                        shopt -s patsub_replacement 2>/dev/null || :
                    else
                        shopt -u patsub_replacement 2>/dev/null || :
                    fi
                    case $operator in
                        '/') printf '%s\0' "${words[@]/$pattern/$replacement}" ;;
                        '//') printf '%s\0' "${words[@]//$pattern/$replacement}" ;;
                        '/#') printf '%s\0' "${words[@]/#$pattern/$replacement}" ;;
                        '/%') printf '%s\0' "${words[@]/%$pattern/$replacement}" ;;
                    esac
                )
                ;;
        esac
    fi
    if [[ ${pattern_list_known+present} ]]; then
        pattern_list_words=("${words[@]}")
        pattern_list_known=1
        return 0
    fi
    local separator=${variable_facts[IFS]-unset}
    # Case and test subjects collapse @ elements with a space. Star
    # expansions and assignments retain the first IFS character.
    if (( ${pattern_list_subject:-0} )) && [[ $name == @ || $index == '[@]' ]]; then
        separator=unset
    fi
    case $separator in
        unset) separator=' ' ;;
        scalar:*) separator=${separator:7:1} ;;
        *) if (( ${#words[@]} > 1 )); then return 0; fi; separator='' ;;
    esac
    local IFS=$separator
    parameter_value=scalar:${words[*]}
}

# @P has prompt escapes, not the @E escape language. Decode only measured,
# context-independent forms; never run prompt substitutions from input data.
resolve_credential_prompt_value() {
    # Bash leaves percent text (including %% and %d) unchanged outside \D{...}.
    [[ $value != *'$'* && $value != *'`'* ]] || return 1
    local input=$value output='' char digits decoded
    while [[ -n $input ]]; do
        char=${input:0:1}; input=${input:1}
        if [[ $char != '\' || -z $input ]]; then output+=$char; continue; fi
        char=${input:0:1}; input=${input:1}
        case $char in
            n) output+=$'\n' ;;
            r) output+=$'\r' ;;
            a) output+=$'\a' ;;
            e) output+=$'\e' ;;
            '\') output+='\' ;;
            '['|']') ;;
            [0-7])
                digits=$char
                while (( ${#digits} < 3 )) && [[ ${input:0:1} == [0-7] ]]; do
                    digits+=${input:0:1}; input=${input:1}
                done
                printf -v decoded '%b' "\0$digits"
                output+=$decoded ;;
            [uHhwWdtTADsvVjl!#'$']) return 1 ;;
            *) output+="\\$char" ;;
        esac
    done
    value=$output
}

# Deterministic expansions share the value path used by tests, assignments,
# patterns and arithmetic. Values are data only: never eval source spelling.
resolve_credential_extended_parameter() {
    local head='^([!#]?)([A-Za-z_][A-Za-z0-9_]*|[0-9@*])(\[[^]]+\])?'
    [[ ${source:position} =~ $head ]] || return 1
    local prefix=${BASH_REMATCH[1]} name=${BASH_REMATCH[2]} index=${BASH_REMATCH[3]}
    local end=$((position + ${#BASH_REMATCH[0]})) tail current value fact bounds offset
    tail=${source:end}
    if [[ -n $prefix ]]; then
        [[ $tail == '}'* ]] || return 1
    elif [[ $tail != @('@Q}'*|'@E}'*|'@P}'*|'@A}'*|'@K}'*|'@a}'*|':'*) ||
            $tail == @(':-'*|':='*|':+'*|':?'*) ]]; then
        return 1
    fi
    local parameter_depth=$(( ${parameter_depth:-0} + 1 ))
    (( parameter_depth < 32 )) || return 1
    position=$end
    if [[ $prefix == '#' && $index == @('[@]'|'[*]') ]]; then
        position=$((position + 1))
        fact=${variable_facts[$name]:-unknown}
        case $fact in
            array:*) bounds=${fact#*:}; parameter_value=scalar:$(( ${bounds#*:} - ${bounds%:*} )) ;;
            scalar:*) parameter_value=scalar:1 ;;
            unset) parameter_value=scalar:0 ;;
        esac
        return 0
    fi
    if [[ $prefix == '!' && ${variable_facts[$name]:-} == nameref:* && -z $index ]]; then
        parameter_value=scalar:${variable_facts[$name]#nameref:}
        position=$((position + 1))
        return 0
    fi
    credential_parameter_fact "$name" "$index"
    if [[ $prefix == '!' ]]; then
        position=$((position + 1))
        [[ $current == scalar:* ]] || return 0
        value=${current#scalar:}
        [[ $value =~ $assignment_target$ ]] || return 0
        credential_parameter_fact "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
        if [[ $current == unset ]]; then current=scalar:; fi
        if [[ $current == scalar:* ]]; then parameter_value=$current; fi
        return 0
    elif [[ $prefix == '#' ]]; then
        position=$((position + 1))
        if [[ $current == unset ]]; then current=scalar:; fi
        if [[ $current == scalar:* ]]; then
            value=${current#scalar:}; parameter_value=scalar:${#value}
        fi
        return 0
    fi
    if [[ $tail == ':'* ]]; then
        local start=$((position + 1)) literal='' word_expansion=0
        local -a env_expansions=() env_expansion_quotes=()
        position=$start
        read_shell_word '}' 0 2 0 0 0 0 0 0 1
        [[ ${source:position-1:1} == '}' ]] || return 0
        resolve_env_word 1 0 1 || return 0
        local expression=$literal split=-1 depth=0 conditional=0 char i length
        # A colon in a parenthesized or ternary expression is not a delimiter.
        for ((i=0; i<${#expression}; i++)); do
            char=${expression:i:1}
            case $char in
                '(') depth=$((depth + 1)) ;;
                ')') depth=$((depth - 1)) ;;
                '?') if (( ! depth )); then conditional=$((conditional + 1)); fi ;;
                ':') if (( ! depth )); then
                    if (( conditional )); then conditional=$((conditional - 1))
                    else split=$i; break; fi
                fi ;;
            esac
        done
        if (( split >= 0 )); then
            offset=$(credential_arithmetic_value "${expression:0:split}") || return 0
            length=$(credential_arithmetic_value "${expression:split+1}") || return 0
        else
            offset=$(credential_arithmetic_value "$expression") || return 0
        fi
        if [[ $name == [@*] ]]; then
            local -a selected=()
            if (( ! ${positional_empty:-0} )); then
                bounds=${positional_bounds:-}
                [[ -n $bounds ]] || return 0
                for ((i=${bounds%:*}; i<${bounds#*:}; i++)); do selected+=("${known_array_words[i]}"); done
            fi
            local count=${#selected[@]}
            if (( offset < 0 )); then offset=$((count + 1 + offset)); fi
            # Offset zero includes $0, whose spelling is not modeled.
            (( offset != 0 )) || return 0
            offset=$((offset - 1))
            if (( split < 0 )); then length=$count; fi
            (( length >= 0 )) || return 0
            if (( offset < 0 || offset >= count )); then selected=()
            else selected=("${selected[@]:offset:length}"); fi
            local separator=${variable_facts[IFS]-unset}
            if (( ${pattern_list_subject:-0} )) && [[ $name == @ ]]; then separator=unset; fi
            case $separator in
                unset) separator=' ' ;;
                scalar:*) separator=${separator:7:1} ;;
                *) (( ${#selected[@]} <= 1 )) || return 0; separator='' ;;
            esac
            if [[ ${pattern_list_known+present} ]]; then
                pattern_list_words=("${selected[@]}")
                pattern_list_known=1
                return 0
            fi
            local IFS=$separator
            parameter_value=scalar:${selected[*]}
            return 0
        fi
        [[ $index != @('[@]'|'[*]') ]] || return 0
        if [[ $current == unset ]]; then current=scalar:; fi
        [[ $current == scalar:* ]] || return 0
        value=${current#scalar:}
        if (( offset < 0 )); then offset=$(( ${#value} + offset )); fi
        if (( offset < 0 || offset > ${#value} )); then parameter_value=scalar:; return 0; fi
        if (( split < 0 )); then length=$(( ${#value} - offset ))
        elif (( length < 0 )); then length=$(( ${#value} + length - offset )); fi
        (( length >= 0 )) || return 0
        parameter_value=scalar:${value:offset:length}
        return 0
    fi
    local operator=${tail:1:1} attrs=''
    position=$((position + 3))
    # Attribute reads follow namerefs and use Bash's canonical letter order.
    resolve_credential_nameref || return 0
    fact=${variable_facts[$name]:-unknown}
    case $fact in
        array:*|partial-array:*) attrs=a ;;
        assoc-array:*) attrs=A ;;
    esac
    if [[ ${integer_facts[$name]:-0} == 1 ]]; then attrs+=i; fi
    if [[ ${readonly_facts[$name]:-0} == 1 ]]; then attrs+=r; fi
    attrs+=${attribute_facts[$name]:-}
    local sorted_attrs='' letter
    for letter in a A i n r t x l u; do
        if [[ $attrs == *"$letter"* ]]; then sorted_attrs+=$letter; fi
    done
    attrs=$sorted_attrs
    if [[ $operator == a && $index != @('[@]'|'[*]') ]]; then
        [[ $fact != unknown && $fact != array-unknown ]] || return 0
        parameter_value=scalar:$attrs
        return 0
    fi
    if [[ $index == @('[@]'|'[*]') ]]; then
        # Associative facts record known writes, not a complete key set (an
        # initializer may be opaque), so serializing the whole map is unsafe.
        [[ $fact == array:* ]] || return 0
        # Reconstruct only literal keys and known elements in a local array.
        # Native transformations preserve Bash quoting without executing data.
        local -a modeled=()
        bounds=${fact#*:}
        local key
        for ((offset=${bounds%:*}; offset<${bounds#*:}; offset++)); do
            [[ ! ${unknown_array_elements[offset]:-} ]] || return 0
            key=${known_array_indices[offset]}
            [[ $key =~ ^[0-9]+$ ]] || return 0
            modeled[$key]=${known_array_words[offset]}
        done
        local -a transformed=()
        case $operator in
            Q) transformed=("${modeled[@]@Q}") ;;
            E) transformed=("${modeled[@]@E}") ;;
            P) for value in "${modeled[@]}"; do
                resolve_credential_prompt_value || return 0
                transformed+=("$value")
            done ;;
            K) transformed=("${modeled[@]@K}") ;;
            a) for value in "${modeled[@]}"; do transformed+=("$attrs"); done ;;
            A)
                transformed=("${modeled[@]@A}")
                transformed[1]=-$attrs
                transformed[2]=$name=${transformed[2]#modeled=} ;;
        esac
        local separator=${variable_facts[IFS]-unset}
        if (( ${pattern_list_subject:-0} )) && [[ $index == '[@]' ]]; then separator=unset; fi
        case $separator in
            unset) separator=' ' ;;
            scalar:*) separator=${separator:7:1} ;;
            *) if (( ${#transformed[@]} > 1 )); then return 0; fi; separator='' ;;
        esac
        local IFS=$separator
        if [[ ${pattern_list_known+present} ]]; then
            if [[ $index == '[*]' ]]; then transformed=("${transformed[*]}"); fi
            pattern_list_words=("${transformed[@]}")
            pattern_list_known=1
            return 0
        fi
        parameter_value=scalar:${transformed[*]}
        return 0
    fi
    if [[ $current == unset ]]; then
        parameter_value=scalar:
        if [[ $operator == A && -n $attrs ]]; then parameter_value="scalar:declare -$attrs $name"; fi
        return 0
    fi
    [[ $current == scalar:* ]] || return 0
    value=${current#scalar:}
    case $operator in
        Q|K) value=${value@Q} ;;
        E) value=${value@E} ;;
        P) resolve_credential_prompt_value || return 0 ;;
        A)
            value=$name=${value@Q}
            if [[ -n $attrs ]]; then value="declare -$attrs $value"; fi ;;
    esac
    parameter_value=scalar:$value
    return 0
}

read_parameter_word() {
    local opening=$1 quoted=${2:-0} state_dependent_credential=1
    if [[ ! ${credential_parameter_scan:-} ]]; then
        local credential_parameter_scan=1
        local -A inactive_parameter_ends=()
    fi
    # Preserve the evaluator's fatal subscript status at expansion time.
    # Value-only resolution loses this status in a command substitution.
    local subscript_head='^[!#]?([A-Za-z_][A-Za-z0-9_]*)(\[[^]]+\])'
    if (( ! ${4:-0} && ! ${skip_expansion_effects:-0} && ! dead_branches )) &&
            [[ ${source:position} =~ $subscript_head ]]; then
        local subject=${BASH_REMATCH[1]} subscript=${BASH_REMATCH[2]}
        if [[ $subscript != @('[@]'|'[*]') && ${variable_facts[$subject]:-} != assoc-array:* ]]; then
            local -a subscript_evaluation=()
            mapfile -t subscript_evaluation < <(arithmetic_emit_writes=1 credential_arithmetic_value "${subscript:1:-1}")
            if (( ${#subscript_evaluation[@]} )) && [[ ${subscript_evaluation[-1]} == @(ABORT|FAIL) ]]; then
                abort_credential_condition || :
                arithmetic_expansion_failed=1
                suppress_command_writes=1
                builtin_failed=1
            fi
        fi
    fi
    if resolve_credential_extended_parameter; then return 0; fi
    local parameter_head='^([A-Za-z_][A-Za-z0-9_]*|[0-9]+|[#*@])(\[|[#%/^,]|:?[=+?-])'
    local parameter_operator='^(:?[=+?-])'
    [[ ${source:position} =~ $parameter_head ]] || return 1
    local name=${BASH_REMATCH[1]} index='' operator start subscript_start
    local current literal='' word_expansion=0 word_glob=0 rhs cursor=0
    local skip_expansion_effects=$(( ${skip_expansion_effects:-0} || dead_branches ))
    position=$((position + ${#name}))
    if [[ ${source:position:1} == '[' ]]; then
        subscript_start=$position
        position=$((position + 1))
        read_shell_word ']' 0 0 0 "$(( ! ${4:-0} ))" 0 0 0 0 0 "$quoted"
        index=${source:subscript_start:position-subscript_start}
    fi
    local list_subject=0
    if [[ $name == [@*] || $index == @('[@]'|'[*]') ]]; then list_subject=1; fi
    case ${source:position:2} in
        '^^'|',,'|'##'|'%%') operator=${source:position:2} ;;
        '^'*|','*|'#'*|'%'*) operator=${source:position:1} ;;
        '//'|'/#'|'/%') operator=${source:position:2} ;;
        '/'*) operator=/ ;;
    esac
    if [[ -n ${operator:-} ]]; then
        local case_pattern='' value pattern_start=$((position + ${#operator}))
        local literal_pattern_only=1 closing_pattern='}' replacement='' pattern_known=1
        local -a env_expansions=() env_expansion_quotes=()
        if [[ $operator == /* ]]; then closing_pattern='/}'; fi
        position=$pattern_start
        read_shell_word "$closing_pattern" 0 2 0 "$(( ! ${4:-0} ))" 0 0 1
        resolve_credential_glob_pattern || pattern_known=0
        local pattern=$case_pattern
        if [[ $operator == @('^'|'^^'|','|',,') && ${source:pattern_start:1} == '}' ]]; then pattern='?'; fi
        # An expanded empty pattern is not Bash's omitted-pattern shorthand.
        # Removing an empty prefix is the identity operation for scalars/lists.
        if [[ $operator == @('^'|'^^'|','|',,') && -z $pattern ]]; then operator='#'; fi
        if [[ $operator == /* && ${source:position-1:1} == / ]]; then
            case_pattern='' literal='' word_expansion=0
            env_expansions=() env_expansion_quotes=()
            local capture_replacement=0
            if (( ${patsub_replacement:-1} )); then capture_replacement=2; fi
            read_shell_word '}' 0 2 0 "$(( ! ${4:-0} ))" 0 0 "$capture_replacement" 0 1
            if (( capture_replacement )); then
                resolve_credential_glob_pattern 2 || pattern_known=0
                replacement=$case_pattern
            else
                resolve_env_word 1 || pattern_known=0
                replacement=$literal
            fi
        fi
        if (( pattern_known && position > pattern_start )) &&
                [[ ${source:position-1:1} == '}' && ${source:pattern_start:1} != '~' ]]; then
            if (( list_subject )) || [[ $operator == /* ]]; then
                resolve_credential_pattern_list "$name" "$operator" "$pattern" "$replacement" "$index"
            else
                credential_parameter_fact "$name" "$index"
                if [[ $current == scalar:* || $current == unset ]]; then
                    value=''
                    if [[ $current == scalar:* ]]; then value=${current#scalar:}; fi
                    case $operator in
                        '#') value=${value#$pattern} ;;
                        '##') value=${value##$pattern} ;;
                        '%') value=${value%$pattern} ;;
                        '%%') value=${value%%$pattern} ;;
                        '^') value=${value^$pattern} ;;
                        '^^') value=${value^^$pattern} ;;
                        ',') value=${value,$pattern} ;;
                        ',,') value=${value,,$pattern} ;;
                    esac
                    parameter_value=scalar:$value
                fi
            fi
        fi
        return 0
    fi
    if [[ ! ${source:position} =~ $parameter_operator ]]; then
        read_shell_word '}' 0 0 0 1 0 0 0 0 0 "$quoted"
        return 0
    fi
    operator=${BASH_REMATCH[1]}
    start=$((position + ${#operator}))
    credential_parameter_fact "$name" "$index"
    if [[ $operator != *= && $name == [A-Za-z_]* && ! ${variable_facts[$name]+present} ]]; then
        current=inherited
    fi
    case $operator in
        *+)
            if [[ $current == unset ||
                ( $operator == ':+' && $current == scalar: ) ]]; then
                skip_expansion_effects=1
            fi
            ;;
        *\?)
            case $current in
                unknown|unset) ;;
                scalar:|set) if [[ $operator == '?' ]]; then skip_expansion_effects=1; fi ;;
                *) skip_expansion_effects=1 ;;
            esac
            ;;
        *)
            case $current in
                unknown|unset) ;;
                scalar:) if [[ $operator != :* ]]; then skip_expansion_effects=1; fi ;;
                set)
                    # A read from unknown input sets the name. Its value can still
                    # make a nested :- fallback reachable.
                    if [[ $operator != ':-' ]]; then skip_expansion_effects=1; fi
                    ;;
                *) skip_expansion_effects=1 ;;
            esac
            ;;
    esac
    position=$start
    read_shell_word '}' 0 0 0 "$(( ! ${4:-0} ))" "$(( ${3:-0} && ! quoted ))" 0 0 0 1 "$quoted"
    if (( ! skip_expansion_effects && word_glob && ! quoted )) && [[ $operator != *\? ]]; then
        outer_default_glob=1
    fi
    if (( skip_expansion_effects )) && [[ ${source:position-1:1} == '}' ]]; then
        inactive_parameter_ends[${logical_start:-0}:$opening]=$position
    fi
    if [[ ${source:position-1:1} == '}' ]]; then
        case $operator in
            :+|+)
                if [[ $current == unset || ( $operator == ':+' && $current == scalar: ) ]]; then
                    parameter_value=scalar:
                elif [[ $current == scalar:* || ( $operator == '+' && $current == set ) ]]; then
                    resolve_credential_default_value "${source:start:position-start}" "$quoted"
                fi
                return 0 ;;
            :\?|\?)
                if [[ $current == unset || ( $operator == ':?' && $current == scalar: ) ]]; then
                    # A later [[ operand may be short-circuited. The linear
                    # effect scan cannot prove its reachability after &&/||.
                    local error_prefix=${source:0:opening}
                    if (( ! skip_expansion_effects )) &&
                            ! { (( ${conditional:-0} )) && [[ $error_prefix == *'&&'* || $error_prefix == *'||'* ]]; }; then
                        abort_credential_shell "${logical_start:-0}:$opening"
                    fi
                elif [[ $current == scalar:* ]]; then parameter_value=$current; fi
                return 0 ;;
        esac
    fi
    # Resolve default words independently of effect scanning. Lookahead skips
    # writes, but an explicitly unset base still proves which value is used.
    if [[ $operator == @(:-|-) && ${source:position-1:1} == '}' ]]; then
        if [[ $current == unset || ( $operator == ':-' && $current == scalar: ) ]]; then
            resolve_credential_default_value "${source:start:position-start}" "$quoted"
        elif [[ $current == scalar:* ]]; then
            parameter_value=$current
        fi
        return 0
    fi
    if [[ $name =~ ^([0-9]+|[#*@])$ ]]; then
        if [[ $operator == @(:-|-) ]]; then
            if (( ! skip_expansion_effects && ! word_expansion )) &&
                    [[ $current == unset || $current == scalar: ]]; then
                parameter_value=scalar:$literal
            elif [[ $current == scalar:* && ( $current != scalar: || $operator == '-' ) ]]; then
                parameter_value=$current
            fi
        elif [[ $name == '@' && $operator == @(:+|+) ]]; then
            if [[ $current == unset || ( $operator == ':+' && $current == scalar: ) ]]; then
                parameter_value=scalar:
            elif [[ $current == scalar:* ]] && (( ! word_expansion && ! skip_expansion_effects )); then
                parameter_value=scalar:$literal
            fi
        elif [[ $name == '@' && $current == scalar:* ]] && (( skip_expansion_effects )); then
            parameter_value=$current
        fi
        return 0
    fi
    if [[ $operator == *= && ${source:position-1:1} == '}' &&
            ( $current == unset || ( $operator == ':=' && $current == scalar: ) ) ]]; then
        resolve_credential_default_value "${source:start:position-start}" "$quoted"
    fi
    if [[ $operator == *= && $current == scalar:* &&
            ( $current != scalar: || $operator == '=' ) ]]; then parameter_value=$current; fi
    if (( skip_expansion_effects )) || [[ $operator != *= || ${source:position-1:1} != '}' ]]; then return 0; fi

    if [[ $name =~ $credential_keyword ]]; then
        # Mask skipped operator words before checking the RHS for literals.
        local offset=$start end=$((position - 1)) inactive_end
        rhs=''
        while (( offset < end )); do
            inactive_end=${inactive_parameter_ends[${logical_start:-0}:$offset]:-0}
            if (( inactive_end > offset && inactive_end <= end )); then
                rhs+='$1'
                offset=$inactive_end
            else
                rhs+=${source:offset:1}
                offset=$((offset + 1))
            fi
        done
        if (( quoted )); then rhs=\"$rhs\"; fi
        if [[ -n $rhs ]] && ! { expansion_only && (( cursor == ${#rhs} )); }; then
            report_credential_assignment "$opening"
        fi
    fi
    if [[ $current != unknown ]]; then
        resolve_credential_nameref || return 0
        local assigned=scalar:$literal
        if (( word_expansion )); then
            local fact=unknown
            resolve_credential_assignment "${source:start:position-start-1}"
            assigned=$fact
        fi
        parameter_value=$assigned
        if [[ -n $index || ${variable_facts[$name]:-} == @(array:*|partial-array:*|assoc-array:*) ]]; then
            assign_credential_element "$name" "${index:-[0]}" "$assigned" 0 1
        else
            change_credential_state "fact:$name" "$assigned" 0 '' 1
        fi
    fi
    return 0
}

assign_credential_element() {
    local name=$1 subscript=$2 assigned=$3 base=${variable_facts[$1]:-unknown}
    local index bounds offset stored_array_fact partial=0 resolved_subscript
    if (( ! ${6:-0} )); then
        resolve_credential_subscript "$name" "$subscript"
        subscript=$resolved_subscript
    fi
    if [[ $subscript == invalid-negative ]]; then
        builtin_failed=1
        abort_credential_condition || :
        return
    fi
    if [[ $base == assoc-array:* && $subscript == \[?*\] ]]; then
        local -A elements=() unknown_elements=()
    elif [[ $base == assoc-array:* ]]; then
        change_credential_state "fact:$name" 'assoc-array:0:0' "${4:-0}"
        return
    elif [[ $subscript =~ ^\[(0|[1-9][0-9]{0,17})\]$ ]]; then
        local -a elements=() unknown_elements=()
    else
        change_credential_state "fact:$name" unknown "${4:-0}"
        return
    fi
    index=${subscript:1:-1}
    case $base in
        array:*|partial-array:*|assoc-array:*)
            if [[ $base == partial-array:* ]]; then partial=1; fi
            bounds=${base#*:}
            for (( offset=${bounds%:*}; offset<${bounds#*:}; offset++ )); do
                elements[${known_array_indices[offset]}]=${known_array_words[offset]}
                unknown_elements[${known_array_indices[offset]}]=${unknown_array_elements[offset]:-}
            done
            ;;
        scalar:*) elements[0]=${base#scalar:} ;;
        unset) ;;
        *) partial=1 ;;
    esac
    if [[ $assigned == unset ]]; then
        unset 'elements[$index]' 'unknown_elements[$index]'
        # Partial arrays still need an explicit absent-element fact.
        if (( partial )) || [[ $base == assoc-array:* ]]; then
            elements[$index]='' unknown_elements[$index]=unset
        fi
    else
        local value=$assigned
        normalize_credential_assignment_fact "$name" "$subscript"
        assigned=$value
        elements[$index]=${assigned#scalar:}
        unknown_elements[$index]=''
        if [[ $assigned != scalar:* ]]; then unknown_elements[$index]=$assigned; fi
    fi
    store_credential_array_fact elements
    bounds=${stored_array_fact#array:}
    offset=${bounds%:*}
    for index in "${!elements[@]}"; do
        if [[ -n ${unknown_elements[$index]:-} ]]; then
            unknown_array_elements[offset]=${unknown_elements[$index]}
            partial=1
        fi
        offset=$((offset + 1))
    done
    if [[ $base == assoc-array:* ]]; then
        stored_array_fact=assoc-$stored_array_fact
    elif (( partial )); then
        stored_array_fact=partial-$stored_array_fact
    fi
    change_credential_state "fact:$name" "$stored_array_fact" "${4:-0}" '' "${5:-0}"
}

skip_credential_substitution_effects() {
    local end=$1 offset
    for offset in "${!substitution_ends[@]}"; do
        if (( offset >= expansion_start && offset < end )); then scanned_substitutions[offset]=1; fi
    done
    for offset in "${!backtick_ends[@]}"; do
        if (( offset >= expansion_start && offset < end )); then scanned_backticks[offset]=1; fi
    done
    for offset in "${!arithmetic_ends[@]}"; do
        if (( offset >= expansion_start && offset < end )); then skipped_arithmetic[offset]=1; fi
    done
}

# Read one shell word, or the body of a nested expansion. Keep its literal
# characters separately so printf formats can be checked after expansion.
# Extraction operands mark expansions with dots. Env split strings use a
# separate marker to preserve opaque words through a second tokenization.
read_shell_word() {
    # Argument expansion precedes redirects, even when a later redirect will
    # prevent the builtin from running. Only builtin writes are suppressed.
    local suppress_command_writes=0
    local closing=${1:-} validate=${2:-0} mark_expansions=${3:-0} char quote='' nested escaped
    local quote_removal_only=${4:-0} track_substitutions=${5:-0}
    local nested_extraction expansion_start track_glob=${6:-0}
    local glob_bracket=0 nested_glob track_braces=${7:-0}
    local brace_text='' brace_start
    local capture_pattern=${8:-0} track_tilde=${9:-0} tilde_position=$position
    local parameter_quoted=${11:-0} nested_quoted outer_default_glob=0
    local parameter_value prior_expansion current reference
    local braced_positional='^\$\{([0-9]+|[#*@])\}'
    local -a brace_starts=()
    local brace_range='^(-?[0-9]+\.\.-?[0-9]+|[A-Za-z]\.\.[A-Za-z])(\.\.-?[0-9]+)?$'
    if [[ -n $closing ]] && (( ! quote_removal_only && ! ${10:-0} )); then local literal=''; fi
    local variable='^\$([A-Za-z_][A-Za-z0-9_]*|[0-9?#@*!$-])'
    local glob_default='^([A-Za-z_][A-Za-z0-9_]*|[0-9]+|[?#@*!$-])(\[[^]]*\])?:?[-=+]'
    local ansi_text ansi_quoted="^(([^'\\\\]|\\\\.)*)'"
    while (( position < ${#source} )); do
        char=${source:position:1}
        if [[ -z $quote ]]; then
            if (( track_tilde && position == tilde_position )) && [[ $char == '~' ]] &&
                    resolves_credential_tilde "${source:position}" "$((track_tilde == 1))"; then
                word_constructed=1
            fi
            if (( track_tilde == 1 )) && [[ $char == : ]]; then tilde_position=$((position + 1)); fi
            if (( track_substitutions )) && [[ $char == [\<\>] && -n ${substitution_ends[position]:-} ]]; then
                word_expansion=1
                if (( ${skip_expansion_effects:-0} )); then
                    expansion_start=$position
                    skip_credential_substitution_effects "$((substitution_ends[position] + 1))"
                else
                    scan_credential_word_substitution "$position" command
                fi
                position=$((substitution_ends[position] + 1))
                continue
            fi
            if [[ $char == "$closing" || ( $closing == '/}' && $char == @('/'|'}') ) ]]; then
                position=$((position + 1))
                return
            fi
            if [[ -z $closing ]]; then
                case $char in
                    [[:space:]]|';'|'&'|'|'|'<'|'>'|'('|')') return 0 ;;
                esac
            fi
        fi
        if [[ -z $quote ]] && (( ! parameter_quoted )); then
            case $char${source:position+1:1} in
                "\$'")
                    delimiter_quoted=1
                    position=$((position + 2))
                    if [[ ${source:position} =~ $ansi_quoted ]]; then
                        ansi_text=${BASH_REMATCH[1]}
                        position=$((position + ${#BASH_REMATCH[0]}))
                        # Bash decodes the escapes without evaluating shell code.
                        ansi_text=${ansi_text@E}
                        literal+=$ansi_text
                        record_credential_regex_part "$ansi_text" 1
                        if (( capture_pattern )); then
                            local ansi_index
                            for (( ansi_index=0; ansi_index<${#ansi_text}; ansi_index++ )); do
                                char=${ansi_text:ansi_index:1}
                                if (( capture_pattern != 3 )) || [[ $char == [\\.\^\$\*+?\(\)\[\]\{\}\|] ]]; then
                                    case_pattern+='\'
                                fi
                                case_pattern+=$char
                            done
                        fi
                        continue
                    fi
                    position=${#source}
                    delimiter_tail=quote
                    return 0
                    ;;
                '$"') position=$((position + 1)); continue ;;
                "'"*|'"'*|\\*) delimiter_quoted=1 ;;
            esac
        fi
        if (( track_braces && ! word_constructed )); then
            # Mask quoted or escaped text so its punctuation cannot complete an expansion.
            if [[ -n $quote || $char == [\'\"\\] ]]; then
                brace_text+=' '
            else
                case $char in
                    '{') brace_starts+=("${#brace_text}") ;;
                    '}')
                        if (( ${#brace_starts[@]} )); then
                            brace_start=$((brace_starts[-1] + 1))
                            unset 'brace_starts[-1]'
                            if [[ ${brace_text:brace_start} == *,* ||
                                  ${brace_text:brace_start} =~ $brace_range ]]; then
                                word_constructed=1
                            fi
                        fi
                        ;;
                esac
                brace_text+=$char
            fi
        fi
        if (( track_glob && ! word_glob )); then
            if [[ -z $quote && $char == [*?] ]]; then
                word_glob=1
            elif [[ $char == "$quote" || ( -z $quote && ( $char == "'" || $char == '"' ) ) ]]; then
                : # Quote delimiters are outside the pattern text.
            elif (( glob_bracket )); then
                if [[ -z $quote && $char == ']' && $glob_bracket == 3 ]]; then
                    word_glob=1
                elif [[ -z $quote && ( $char == '!' || $char == '^' ) && $glob_bracket == 1 ]]; then
                    glob_bracket=2
                else
                    glob_bracket=3
                fi
            elif [[ -z $quote && $char == '[' ]]; then
                glob_bracket=1
            fi
        fi
        position=$((position + 1))
        if [[ $char == '\' && $quote != "'" ]]; then
            escaped=${source:position:1}
            if (( quote_removal_only )) && [[ -z $escaped ]]; then
                delimiter_tail=continuation
                return 0
            fi
            if [[ -z $quote || $escaped == [\$\`\"\\] ]]; then
                literal+=$escaped
                record_credential_regex_part "$escaped" 1
                if (( capture_pattern == 1 )) ||
                        { (( capture_pattern == 3 )) && [[ $escaped == [\\.\^\$\*+?\(\)\[\]\{\}\|] ]]; } ||
                        { (( capture_pattern == 2 )) && [[ $escaped == [\\\&] ]]; }; then
                    case_pattern+="\\$escaped"
                elif (( capture_pattern )); then
                    case_pattern+=$escaped
                fi
                position=$((position + (${#source} > position)))
            else
                literal+=$char
                record_credential_regex_part "$char" 1
                if (( capture_pattern )); then case_pattern+="\\$char"; fi
            fi
        elif [[ $char == "$quote" ]]; then
            quote=''
        elif [[ -z $quote && ( ( $char == "'" && $parameter_quoted == 0 ) || $char == '"' ) ]]; then
            quote=$char
        elif [[ $quote_removal_only == 0 && $quote != "'" && ( $char == '$' || $char == '`' ) ]]; then
            expansion_start=$((position - 1))
            case $char${source:position:1} in
                '$('|'${')
                    # Evaluate arithmetic expansions at their word position so
                    # later words see writes, and the result uses pre-write facts.
                    # Insert the numeric result directly to avoid a second read
                    # of an expression such as x++ when resolving marked words.
                    if (( track_substitutions && ${scan_word_substitutions:-0} &&
                            ! ${skip_expansion_effects:-0} )) &&
                            [[ -n ${arithmetic_ends[expansion_start]:-} ]]; then
                        local arithmetic_end=${arithmetic_ends[expansion_start]}
                        local arithmetic_assignment_result='' arithmetic_assignment_failed=0 arithmetic_assignment_fatal=0
                        if (( ! ${allowed:-0} )); then
                            scan_credential_arithmetic "$((expansion_start + 3))" "$((arithmetic_end - 1))" 1
                        fi
                        apply_credential_arithmetic_assignment "${source:expansion_start+3:arithmetic_end-expansion_start-4}"
                        position=$((arithmetic_end + 1))
                        skipped_arithmetic[expansion_start]=1
                        if (( arithmetic_assignment_failed )); then
                            if (( arithmetic_assignment_fatal )); then abort_credential_condition || :; fi
                            arithmetic_expansion_failed=1
                            suppress_command_writes=1
                            builtin_failed=1
                        elif [[ -n $arithmetic_assignment_result ]]; then
                            literal+=$arithmetic_assignment_result
                            record_credential_regex_part "$arithmetic_assignment_result" "${quote:+1}"
                            if (( capture_pattern )); then case_pattern+=$arithmetic_assignment_result; fi
                        else
                            word_expansion=1
                            if (( mark_expansions == 1 )); then literal+=.; fi
                            if (( mark_expansions == 2 )); then record_env_expansion "$expansion_start"; fi
                        fi
                        continue
                    fi
                    if (( track_substitutions )) && [[ ${source:position:1} == '(' &&
                            -n ${substitution_ends[position-1]:-} ]]; then
                        word_expansion=1
                        if (( mark_expansions == 1 )); then literal+=.; fi
                        if (( ${skip_expansion_effects:-0} )); then
                            skip_credential_substitution_effects "$((substitution_ends[position-1] + 1))"
                        else
                            scan_credential_word_substitution "$((position - 1))" command
                        fi
                        position=$((substitution_ends[position-1] + 1))
                        if (( mark_expansions == 2 )); then record_env_expansion "$expansion_start"; fi
                        continue
                    fi
                    # Re-reading an assignment RHS has no original offset
                    # table. Reuse the already scanned substitution's complete
                    # spelling, including case-pattern closing parentheses.
                    if (( ! track_substitutions && mark_expansions == 2 )) &&
                            [[ ${source:position:1} == '(' ]]; then
                        local cached_reference cached_length=0
                        for cached_reference in "${!substitution_values[@]}"; do
                            if [[ $cached_reference == '$('* &&
                                  ${source:expansion_start} == "$cached_reference"* ]] &&
                                    (( ${#cached_reference} > cached_length )); then
                                cached_length=${#cached_reference}
                            fi
                        done
                        if (( cached_length )); then
                            word_expansion=1
                            position=$((expansion_start + cached_length))
                            record_env_expansion "$expansion_start"
                            continue
                        fi
                    fi
                    if (( validate )); then
                        nested_extraction=unquoted
                        if [[ $quote == '"' ]]; then nested_extraction=quoted; fi
                        cursor=$((position - 1))
                        consume_expansion || return 1
                        position=$cursor
                        continue
                    fi
                    if (( track_substitutions && ! mark_expansions && ! ${literal_pattern_only:-0} )) &&
                            [[ ${source:expansion_start} =~ $braced_positional ]]; then
                        reference=${BASH_REMATCH[1]}
                        position=$((expansion_start + ${#BASH_REMATCH[0]}))
                        credential_special_parameter_fact "$reference"
                        case $current in
                            scalar:*) literal+=${current#scalar:} ;;
                            unset) ;;
                            *) word_expansion=1 ;;
                        esac
                        continue
                    fi
                    prior_expansion=${word_expansion:-0}
                    word_expansion=1
                    if (( mark_expansions == 1 )); then literal+=.; fi
                    nested=')'
                    if [[ ${source:position:1} == '{' ]]; then nested='}'; fi
                    position=$((position + 1))
                    nested_quoted=0
                    if [[ $nested == '}' && ${source:position} =~ $parameter_word_prefix ]] &&
                            { [[ $quote == '"' ]] || (( parameter_quoted )); }; then
                        nested_quoted=1
                    fi
                    parameter_value=unknown
                    if (( track_substitutions && ${scan_word_substitutions:-0} && ! ${allowed:-0} )) &&
                            [[ $nested == '}' ]] && scan_credential_parameter "$expansion_start" "$nested_quoted" "$track_glob"; then
                        if (( outer_default_glob )); then word_glob=1; fi
                        if (( ! mark_expansions && ! ${literal_pattern_only:-0} )) && [[ $parameter_value == scalar:* ]]; then
                            literal+=${parameter_value#scalar:}
                            word_expansion=$prior_expansion
                        fi
                        if (( mark_expansions == 2 )); then record_env_expansion "$expansion_start"; fi
                        continue
                    fi
                    nested_glob=0
                    if (( track_glob )) && [[ -z $quote && $nested == '}' &&
                            ${source:position} =~ $glob_default ]]; then
                        position=$((position + ${#BASH_REMATCH[0]}))
                        nested_glob=1
                    fi
                    if (( ${skip_expansion_effects:-0} )) && [[ -n ${arithmetic_ends[expansion_start]:-} ]]; then
                        skip_credential_substitution_effects "$((arithmetic_ends[expansion_start] + 1))"
                    fi
                    read_shell_word "$nested" 0 0 0 "$track_substitutions" "$nested_glob" 0 0 0 0 "$nested_quoted"
                    if (( mark_expansions == 2 )); then record_env_expansion "$expansion_start"; fi
                    ;;
                '`'*)
                    (( ! validate )) || return 1
                    word_expansion=1
                    if (( mark_expansions == 1 )); then literal+=.; fi
                    if (( track_substitutions )) && [[ -n ${backtick_ends[position-1]:-} ]]; then
                        if (( ${skip_expansion_effects:-0} )); then
                            skip_credential_substitution_effects "$((backtick_ends[position-1] + 1))"
                        else
                            scan_credential_word_substitution "$((position - 1))" backtick
                        fi
                        position=$((backtick_ends[position-1] + 1))
                    else
                        read_shell_word '`' 0 0 0 "$track_substitutions"
                    fi
                    if (( mark_expansions == 2 )); then record_env_expansion "$expansion_start"; fi
                    ;;
                *)
                    if [[ ${source:position-1} =~ $variable ]]; then
                        reference=${BASH_REMATCH[1]}
                        position=$((position - 1 + ${#BASH_REMATCH[0]}))
                        current=unknown
                        if (( track_substitutions && ! mark_expansions && ! ${literal_pattern_only:-0} )) && [[ $reference == [0-9#*@] ]]; then
                            credential_special_parameter_fact "$reference"
                        fi
                        case $current in
                            scalar:*) literal+=${current#scalar:} ;;
                            unset) ;;
                            *) word_expansion=1 ;;
                        esac
                        if (( mark_expansions == 1 )); then literal+=.; fi
                        if (( mark_expansions == 2 )); then record_env_expansion "$expansion_start"; fi
                    else
                        literal+=$char
                        record_credential_regex_part "$char"
                        if (( capture_pattern == 3 )) && [[ -z $quote ]]; then
                            case_pattern+=$char
                        elif (( capture_pattern )); then
                            case_pattern+="\\$char"
                        fi
                    fi
                    ;;
            esac
        elif [[ -z $quote && $closing == ')' && $char == '(' ]]; then
            read_shell_word ')' 0 0 0 "$track_substitutions"
        else
            literal+=$char
            record_credential_regex_part "$char"
            if (( capture_pattern )); then
                if [[ -n $quote ]] &&
                        { (( capture_pattern == 1 )) ||
                          { (( capture_pattern == 3 )) && [[ $char == [\\.\^\$\*+?\(\)\[\]\{\}\|] ]]; } ||
                          { (( capture_pattern == 2 )) && [[ $char == [\\\&] ]]; }; }; then
                    case_pattern+='\'
                fi
                case_pattern+=$char
            fi
        fi
    done
    if (( quote_removal_only )) && [[ -n $quote ]]; then delimiter_tail=quote; fi
}

read_printf_argument() {
    local space='^[[:blank:]]*' boundary='^[[:space:];&|<>()#]'
    while [[ ${source:position} =~ $space ]]; do
        position=$((position + ${#BASH_REMATCH[0]}))
        skip_credential_redirection || break
    done
    [[ -n ${source:position} && ! ${source:position} =~ $boundary ]] || return 1
    start=$position
    literal=''
    word_expansion=0
    read_shell_word '' 0 "${1:-0}"
}

printf_assignment_has_literal() {
    local name rhs cursor start literal='' word_expansion
    local directive='%(%|[-+ #0]*([0-9]+|\*)?(\.([0-9]*|\*))?([A-Za-z]|\([^)]*\)T))'
    local escape='\\([abcefnrtv\\"'"'"']|[0-7]{1,3}|x[[:xdigit:]]{1,2}|u[[:xdigit:]]{1,4}|U[[:xdigit:]]{1,8})'
    local format_part="^($directive|$escape|[[:space:]:._-])"

    # Mark expansions so a dynamic subscript can match without making a
    # dynamic base name look like a static credential variable.
    read_printf_argument 1 || return 1
    case $literal in
        -v)
            read_printf_argument 1 || return 1
            name=$literal
            ;;
        -v*) name=${literal#-v} ;;
        *) return 1 ;;
    esac
    [[ $name =~ $assignment_target$ ]] || return 1
    [[ ${BASH_REMATCH[1]} =~ $credential_keyword ]] || return 1

    read_printf_argument || return 1
    rhs=${source:start:position-start}
    cursor=0
    if ! expansion_only; then
        while [[ $literal =~ $format_part ]]; do
            literal=${literal:${#BASH_REMATCH[0]}}
        done
        [[ -z $literal ]] || return 0
    fi
    while read_printf_argument; do
        rhs=${source:start:position-start}
        cursor=0
        expansion_only || return 0
    done
    return 1
}

find . -name .git -prune -o \( -type f -o -type l -o -type d \) -print0 2>/dev/null |
(
    violations=0
    while IFS= read -r -d '' path; do
        if [[ -d $path && ! -L $path ]]; then
            case ${path##*/} in
                .kotlin|.superpowers)
                    contents=("$path"/* "$path"/.[!.]* "$path"/..?*)
                    for entry in "${contents[@]}"; do
                        if [[ -e $entry || -L $entry ]]; then
                            report - junk-dir
                            break
                        fi
                    done
                    ;;
            esac
            continue
        fi
        if [[ $path == ./tools/check_repo_hygiene.sh ]] && cmp -s -- "$path" "$checker_source"; then
            continue
        fi
        case ${path##*/} in
            *.log|local.properties|signing.properties|*.jks|*.keystore|google-services.json) report - junk-file ;;
        esac
        [[ -f $path && ! -L $path ]] || continue

        scan private-ip "$private_ip"
        scan local-path "$local_path"
        if [[ ${path#./} != "$TOKEN_FORMAT_ALLOW_FILE" ]]; then
            scan token-format "$token_format"
        fi
        if [[ $path == *.sh ]]; then
            scan_credentials < "$path"
        fi
        if [[ $path == *.kt || $path == *.kts ]]; then
            # Kotlin qualified receivers use this@Label.member syntax.
            scan email "(?!this@[A-Z])$email"
        else
            scan email "$email"
        fi
        if [[ $path != *.tsv ]]; then
            scan phone "$phone"
        fi
    done
    printf 'Hygiene violations: %d\n' "$violations"
    (( violations == 0 ))
)
